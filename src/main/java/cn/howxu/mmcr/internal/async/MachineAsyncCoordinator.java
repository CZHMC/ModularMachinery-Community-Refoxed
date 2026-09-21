package cn.howxu.mmcr.internal.async;

import cn.howxu.mmcr.config.ServerConfig;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Coordinates worker continuations and their server-thread commit steps for one level.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MachineAsyncCoordinator {
    private static final Map<ServerLevel, MachineAsyncCoordinator> COORDINATORS = new WeakHashMap<>();
    private static final int WORKER_COUNT = ServerConfig.asyncWorkerCount();
    private static final AtomicInteger WORKER_THREAD_ID = new AtomicInteger(1);
    private static final ThreadFactory WORKER_THREAD_FACTORY = runnable ->
            new Thread(runnable, "MMCR-AsyncWorker-" + WORKER_THREAD_ID.getAndIncrement());
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(WORKER_COUNT, WORKER_COUNT,
            0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), WORKER_THREAD_FACTORY);
    private static final int MAX_STALLED_FENCE_PASSES = 5;

    private final Executor executor;
    private final @Nullable Runnable beforePendingMainStep;
    private final ConcurrentSkipListMap<Long, TickBatch> batches = new ConcurrentSkipListMap<>();
    private final Map<TaskKey, Task> tasks = new ConcurrentHashMap<>();
    private final Map<TaskKey, MainThreadStepExecutor> mainStepExecutors = new ConcurrentHashMap<>();
    private final Map<TaskKey, MainThreadStep.Result.Failure> failures = new ConcurrentHashMap<>();
    private final Object progressMonitor = new Object();
    private final AtomicInteger progress = new AtomicInteger();

    private MachineAsyncCoordinator(Executor executor) {
        this(executor, null);
    }

    private MachineAsyncCoordinator(Executor executor, @Nullable Runnable beforePendingMainStep) {
        this.executor = executor;
        this.beforePendingMainStep = beforePendingMainStep;
    }

    public static synchronized MachineAsyncCoordinator get(ServerLevel level) {
        return COORDINATORS.computeIfAbsent(level, ignored -> new MachineAsyncCoordinator(WORKERS));
    }

    public static MachineAsyncCoordinator forTesting(Executor executor) {
        return new MachineAsyncCoordinator(executor);
    }

    static MachineAsyncCoordinator forTesting(Executor executor, Runnable beforePendingMainStep) {
        return new MachineAsyncCoordinator(executor, beforePendingMainStep);
    }

    static MachineAsyncCoordinator forTesting(Executor executor, int workerCount) {
        return new MachineAsyncCoordinator(executor);
    }

    public boolean submit(TaskKey key, AsyncContinuation continuation) {
        return submitDetailed(key, continuation, null) == SubmissionResult.ACCEPTED;
    }

    public boolean submit(TaskKey key, AsyncContinuation continuation, @Nullable MainThreadStepExecutor mainStepExecutor) {
        return submitDetailed(key, continuation, mainStepExecutor) == SubmissionResult.ACCEPTED;
    }

    public SubmissionResult submitDetailed(TaskKey key, AsyncContinuation continuation,
                                           @Nullable MainThreadStepExecutor mainStepExecutor) {
        Task task = new Task(key);
        if (tasks.putIfAbsent(key, task) != null) return SubmissionResult.DUPLICATE;
        TickBatch batch = batches.computeIfAbsent(key.gameTime(), TickBatch::new);
        batch.tasks.put(key, task);
        if (mainStepExecutor != null) mainStepExecutors.put(key, mainStepExecutor);
        if (schedule(batch, task, continuation)) return SubmissionResult.ACCEPTED;
        removeTask(batch, task);
        return SubmissionResult.REJECTED;
    }

    public void pumpMainThreadSteps() {
        for (TickBatch batch : batches.values()) pumpMainThreadSteps(batch);
    }

    public void completeTick() {
        completeTick(() -> 0);
    }

    /** Completes the earliest tick batch together with shared-IO arbitration. */
    public void completeTick(IntSupplier resolveSharedIo) {
        Objects.requireNonNull(resolveSharedIo, "resolveSharedIo");
        Map.Entry<Long, TickBatch> entry = batches.firstEntry();
        if (entry == null) return;
        TickBatch batch = entry.getValue();
        int stalledPasses = 0;
        while (batch.hasLiveTask()) {
            int progressBefore = progress.get();
            admitWaitingWorkers(batch);
            pumpMainThreadSteps(batch);
            if (resolveSharedIo.getAsInt() > 0) signalProgress();
            if (!batch.hasLiveTask()) break;
            if (progress.get() != progressBefore) {
                stalledPasses = 0;
                continue;
            }
            if (!batch.deferredMainSteps.isEmpty()) {
                pumpNewerMainThreadSteps(batch.gameTime);
                return;
            }
            if (batch.runningWorkers.get() > 0) {
                awaitWorkerProgress(progressBefore);
                if (progress.get() != progressBefore) {
                    stalledPasses = 0;
                    continue;
                }
            }
            if (++stalledPasses >= MAX_STALLED_FENCE_PASSES) failStalledTasks(batch);
        }
        for (Task task : batch.tasks.values()) {
            if (task.finished || task.cancelled) removeTask(batch, task);
        }
        batches.remove(batch.gameTime, batch);
    }

    public void cancel(BlockPos controllerPos) {
        for (Task task : tasks.values()) {
            if (!task.key.controllerPos().equals(controllerPos)) continue;
            TickBatch batch = batches.get(task.key.gameTime());
            if (batch == null) continue;
            synchronized (task) {
                task.cancelled = true;
            }
            removeTask(batch, task);
        }
    }

    public void cancel(TaskKey key) {
        Task task = tasks.get(key);
        TickBatch batch = task == null ? null : batches.get(key.gameTime());
        if (task == null || batch == null) return;
        synchronized (task) {
            task.cancelled = true;
        }
        removeTask(batch, task);
    }

    public void resume(TaskKey key) {
        resume(key, MainThreadStep.Result.success());
    }

    public void resume(TaskKey key, MainThreadStep.Result result) {
        Task task = tasks.get(key);
        TickBatch batch = task == null ? null : batches.get(key.gameTime());
        if (task == null || batch == null) return;
        PendingMainStep pending = batch.deferredMainSteps.remove(key);
        if (pending == null) return;
        pending.results.add(result);
        executePendingMainStep(batch, pending);
    }

    public static synchronized void discard(ServerLevel level) {
        MachineAsyncCoordinator coordinator = COORDINATORS.remove(level);
        if (coordinator != null) coordinator.cancelAll();
    }

    public MainThreadStep.Result.@Nullable Failure failureFor(TaskKey key) {
        return failures.get(key);
    }

    boolean hasPendingMainStepForTesting() {
        return batches.values().stream().anyMatch(batch -> !batch.pendingMainSteps.isEmpty());
    }

    private boolean schedule(TickBatch batch, Task task, AsyncContinuation continuation) {
        batch.runningWorkers.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    if (!task.cancelled) handleYield(batch, task,
                            continuation.advance(new AsyncExecutionContext(task.key)));
                } catch (Throwable throwable) {
                    fail(batch, task, throwable);
                } finally {
                    batch.runningWorkers.decrementAndGet();
                    signalProgress();
                }
            });
            return true;
        } catch (RuntimeException exception) {
            batch.runningWorkers.decrementAndGet();
            return false;
        }
    }

    private void admitWaitingWorkers(TickBatch batch) {
        while (true) {
            WorkerSegment segment = batch.waitingWorkers.peek();
            if (segment == null || !schedule(batch, segment.task, segment.continuation)) return;
            batch.waitingWorkers.poll();
        }
    }

    private void handleYield(TickBatch batch, Task task, AsyncContinuation.Yield yielded) {
        if (yielded instanceof AsyncContinuation.Yield.Complete) {
            task.finished = true;
            signalProgress();
        } else if (yielded instanceof AsyncContinuation.Yield.MainThread mainThread) {
            batch.pendingMainSteps.add(new PendingMainStep(task, List.of(mainThread.step()),
                    results -> mainThread.resume().apply(results.getFirst())));
        } else if (yielded instanceof AsyncContinuation.Yield.MainThreadBatch mainThreadBatch) {
            batch.pendingMainSteps.add(new PendingMainStep(task, mainThreadBatch.steps(), mainThreadBatch.resume()));
        }
    }

    private void pumpMainThreadSteps(TickBatch batch) {
        PendingMainStep pending;
        while ((pending = batch.pendingMainSteps.poll()) != null) executePendingMainStep(batch, pending);
    }

    private void pumpNewerMainThreadSteps(long gameTime) {
        for (TickBatch batch : batches.tailMap(gameTime, false).values()) pumpMainThreadSteps(batch);
    }

    private void executePendingMainStep(TickBatch batch, PendingMainStep pending) {
        if (beforePendingMainStep != null) beforePendingMainStep.run();
        synchronized (pending.task) {
            if (pending.task.cancelled) return;
            while (pending.nextStep < pending.steps.size()) {
                MainThreadStep step = pending.steps.get(pending.nextStep);
                MainThreadStep.Result result;
                try {
                    MainThreadStepExecutor executor = mainStepExecutors.get(pending.task.key);
                    result = executor == null ? step.execute() : executor.execute(pending.task.key, step);
                } catch (Throwable throwable) {
                    result = MainThreadStep.Result.failure(throwable);
                }
                if (result instanceof MainThreadStep.Result.Pending) {
                    pending.nextStep++;
                    batch.deferredMainSteps.put(pending.task.key, pending);
                    signalProgress();
                    return;
                }
                if (result instanceof MainThreadStep.Result.Failure failure) {
                    fail(batch, pending.task, failure.cause());
                    return;
                }
                pending.results.add(result);
                pending.nextStep++;
            }
        }
        AsyncContinuation continuation;
        try {
            continuation = pending.resume.apply(List.copyOf(pending.results));
        } catch (Throwable throwable) {
            fail(batch, pending.task, throwable);
            return;
        }
        if (!schedule(batch, pending.task, continuation)) batch.waitingWorkers.add(new WorkerSegment(pending.task, continuation));
    }

    private void fail(TickBatch batch, Task task, Throwable throwable) {
        failures.put(task.key, MainThreadStep.Result.failure(throwable));
        task.finished = true;
        removeTask(batch, task);
    }

    private void failStalledTasks(TickBatch batch) {
        for (Task task : batch.tasks.values()) {
            synchronized (task) {
                task.cancelled = true;
            }
            fail(batch, task, new IllegalStateException("Async continuation made no tick-fence progress"));
        }
    }

    private void removeTask(TickBatch batch, Task task) {
        tasks.remove(task.key, task);
        batch.tasks.remove(task.key, task);
        batch.deferredMainSteps.remove(task.key);
        mainStepExecutors.remove(task.key);
    }

    private void cancelAll() {
        for (TickBatch batch : batches.values()) {
            for (Task task : batch.tasks.values()) {
                synchronized (task) {
                    task.cancelled = true;
                }
            }
        }
        batches.clear();
        tasks.clear();
        mainStepExecutors.clear();
        failures.clear();
    }

    private void signalProgress() {
        progress.incrementAndGet();
        synchronized (progressMonitor) {
            progressMonitor.notifyAll();
        }
    }

    private void awaitWorkerProgress(int progressBefore) {
        synchronized (progressMonitor) {
            if (progress.get() != progressBefore) return;
            try {
                progressMonitor.wait(ServerConfig.asyncWorkerProgressWaitMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public enum SubmissionResult { ACCEPTED, DUPLICATE, REJECTED }

    public record TaskKey(BlockPos controllerPos, long gameTime, MachineWorkMode workMode, String laneId,
                          long lifecycleEpoch) {
        public TaskKey(BlockPos controllerPos, long gameTime) {
            this(controllerPos, gameTime, MachineWorkMode.ASYNC, "base", 0L);
        }

        public TaskKey(BlockPos controllerPos, long gameTime, MachineWorkMode workMode) {
            this(controllerPos, gameTime, workMode, "base", 0L);
        }

        public TaskKey(BlockPos controllerPos, long gameTime, MachineWorkMode workMode, String laneId) {
            this(controllerPos, gameTime, workMode, laneId, 0L);
        }

        public TaskKey {
            controllerPos = controllerPos.immutable();
            workMode = Objects.requireNonNull(workMode);
            laneId = Objects.requireNonNull(laneId);
        }
    }

    private static final class Task {
        private final TaskKey key;
        private volatile boolean cancelled;
        private volatile boolean finished;

        private Task(TaskKey key) {
            this.key = key;
        }
    }

    private static final class TickBatch {
        private final long gameTime;
        private final Map<TaskKey, Task> tasks = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<PendingMainStep> pendingMainSteps = new ConcurrentLinkedQueue<>();
        private final Map<TaskKey, PendingMainStep> deferredMainSteps = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<WorkerSegment> waitingWorkers = new ConcurrentLinkedQueue<>();
        private final AtomicInteger runningWorkers = new AtomicInteger();

        private TickBatch(long gameTime) {
            this.gameTime = gameTime;
        }

        private boolean hasLiveTask() {
            return tasks.values().stream().anyMatch(task -> !task.cancelled && !task.finished);
        }
    }

    private static final class PendingMainStep {
        private final Task task;
        private final List<MainThreadStep> steps;
        private final Function<List<MainThreadStep.Result>, AsyncContinuation> resume;
        private final List<MainThreadStep.Result> results = new ArrayList<>();
        private int nextStep;

        private PendingMainStep(Task task, List<MainThreadStep> steps,
                                Function<List<MainThreadStep.Result>, AsyncContinuation> resume) {
            this.task = task;
            this.steps = steps;
            this.resume = resume;
        }
    }

    private record WorkerSegment(Task task, AsyncContinuation continuation) {
    }

    @FunctionalInterface
    public interface MainThreadStepExecutor {
        MainThreadStep.Result execute(TaskKey key, MainThreadStep step);
    }
}
