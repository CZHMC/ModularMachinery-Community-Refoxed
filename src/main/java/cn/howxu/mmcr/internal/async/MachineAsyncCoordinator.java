package cn.howxu.mmcr.internal.async;

import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Coordinates worker continuations and their server-thread commit steps for one level.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MachineAsyncCoordinator {
    private static final Map<ServerLevel, MachineAsyncCoordinator> COORDINATORS = new WeakHashMap<>();
    private static final int WORKER_COUNT = Math.min(Math.max(Runtime.getRuntime().availableProcessors() / 4, 4), 8);
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(WORKER_COUNT, WORKER_COUNT,
            0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private static final int MAX_STALLED_FENCE_PASSES = 2;

    private final Executor executor;
    private final @Nullable Runnable beforePendingMainStep;
    private final Map<TaskKey, Task> tasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PendingMainStep> pendingMainSteps = new ConcurrentLinkedQueue<>();
    private final Map<TaskKey, PendingMainStep> deferredMainSteps = new ConcurrentHashMap<>();
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

    public boolean submit(TaskKey key, AsyncContinuation continuation) {
        return submit(key, continuation, null);
    }

    public boolean submit(TaskKey key, AsyncContinuation continuation, @Nullable MainThreadStepExecutor mainStepExecutor) {
        Task task = new Task(key);
        if (tasks.putIfAbsent(key, task) != null) {
            return false;
        }
        if (mainStepExecutor != null) mainStepExecutors.put(key, mainStepExecutor);
        schedule(task, continuation);
        return true;
    }

    public void pumpMainThreadSteps() {
        pumpMainThreadSteps(null);
    }

    public void completeTick() {
        completeTick(() -> 0);
    }

    /**
     * Completes the current tick's continuations together with shared-IO arbitration.
     * A stalled worker is cancelled rather than keeping the server thread in an unbounded wait.
     */
    public void completeTick(IntSupplier resolveSharedIo) {
        Objects.requireNonNull(resolveSharedIo, "resolveSharedIo");
        Long gameTime = tasks.values().stream()
                .map(task -> task.key.gameTime())
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (gameTime == null) {
            return;
        }
        int stalledPasses = 0;
        while (hasTaskFor(gameTime)) {
            int progressBefore = progress.get();
            pumpMainThreadSteps(gameTime);
            if (resolveSharedIo.getAsInt() > 0) signalProgress();
            if (!hasTaskFor(gameTime)) break;
            if (progress.get() != progressBefore) {
                stalledPasses = 0;
                continue;
            }
            if (hasDeferredMainStepFor(gameTime)) {
                return;
            }
            if (hasActiveWorkerFor(gameTime)) {
                awaitWorkerProgress(progressBefore);
                if (progress.get() != progressBefore) {
                    stalledPasses = 0;
                    continue;
                }
            }
            if (++stalledPasses >= MAX_STALLED_FENCE_PASSES) {
                failStalledTasks(gameTime);
            }
        }
        tasks.entrySet().removeIf(entry -> entry.getKey().gameTime() == gameTime && entry.getValue().finished);
        mainStepExecutors.keySet().removeIf(key -> !tasks.containsKey(key));
    }

    public void cancel(BlockPos controllerPos) {
        for (Task task : tasks.values()) {
            if (!task.key.controllerPos().equals(controllerPos)) continue;
            if (!tasks.remove(task.key, task)) continue;
            synchronized (task) {
                task.cancelled = true;
            }
            pendingMainSteps.removeIf(pending -> pending.task == task);
            deferredMainSteps.remove(task.key);
            mainStepExecutors.remove(task.key);
        }
    }

    /** Resumes a continuation whose main-thread step deferred to an external main-thread arbiter. */
    public void resume(TaskKey key) {
        PendingMainStep pending = deferredMainSteps.remove(key);
        if (pending != null) {
            signalProgress();
            scheduleResume(pending.task, pending.resume, MainThreadStep.Result.success());
        }
    }

    public static synchronized void discard(ServerLevel level) {
        COORDINATORS.remove(level);
    }

    public MainThreadStep.Result.@Nullable Failure failureFor(TaskKey key) {
        return failures.get(key);
    }

    private void pumpMainThreadSteps(@Nullable Long gameTime) {
        int remaining = pendingMainSteps.size();
        while (remaining-- > 0) {
            PendingMainStep pending = pendingMainSteps.poll();
            if (pending == null) {
                return;
            }
            if (gameTime != null && pending.task.key.gameTime() != gameTime) {
                pendingMainSteps.add(pending);
                continue;
            }
            if (beforePendingMainStep != null) beforePendingMainStep.run();
            MainThreadStep.Result result;
            synchronized (pending.task) {
                if (pending.task.cancelled) {
                    continue;
                }
                try {
                    MainThreadStepExecutor executor = mainStepExecutors.get(pending.task.key);
                    result = executor == null ? pending.step.execute() : executor.execute(pending.task.key, pending.step);
                } catch (Throwable throwable) {
                    result = MainThreadStep.Result.failure(throwable);
                }
            }
            if (result instanceof MainThreadStep.Result.Pending) {
                deferredMainSteps.put(pending.task.key, pending);
                signalProgress();
            } else if (result instanceof MainThreadStep.Result.Failure failure) {
                fail(pending.task, failure.cause());
            } else {
                scheduleResume(pending.task, pending.resume, result);
            }
        }
    }

    private void scheduleResume(Task task, java.util.function.Function<MainThreadStep.Result, AsyncContinuation> resume,
                                 MainThreadStep.Result result) {
        task.activeWorkers.incrementAndGet();
        executor.execute(() -> {
            try {
                if (!task.cancelled) {
                    scheduleResult(task, resume.apply(result));
                }
            } catch (Throwable throwable) {
                fail(task, throwable);
            } finally {
                task.activeWorkers.decrementAndGet();
            }
        });
    }

    private void schedule(Task task, AsyncContinuation continuation) {
        task.activeWorkers.incrementAndGet();
        executor.execute(() -> {
            try {
                scheduleResult(task, continuation);
            } catch (Throwable throwable) {
                fail(task, throwable);
            } finally {
                task.activeWorkers.decrementAndGet();
            }
        });
    }

    private void scheduleResult(Task task, AsyncContinuation continuation) {
        synchronized (task) {
            if (task.cancelled) {
                return;
            }
        }
        handleYield(task, continuation.advance(new AsyncExecutionContext(task.key)));
    }

    private void handleYield(Task task, AsyncContinuation.Yield yielded) {
        if (yielded instanceof AsyncContinuation.Yield.Complete) {
            task.finished = true;
            signalProgress();
        } else if (yielded instanceof AsyncContinuation.Yield.MainThread mainThread) {
            synchronized (task) {
                if (!task.cancelled) {
                    pendingMainSteps.add(new PendingMainStep(task, mainThread.step(), mainThread.resume()));
                    signalProgress();
                }
            }
        }
    }

    private void fail(Task task, Throwable throwable) {
        failures.put(task.key, MainThreadStep.Result.failure(throwable));
        task.finished = true;
        signalProgress();
    }

    private void awaitWorkerProgress(int progressBefore) {
        synchronized (progressMonitor) {
            if (progress.get() != progressBefore) return;
            try {
                progressMonitor.wait(1L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void failStalledTasks(long gameTime) {
        for (Task task : tasks.values()) {
            if (task.key.gameTime() != gameTime || task.cancelled || task.finished) continue;
            synchronized (task) {
                task.cancelled = true;
            }
            pendingMainSteps.removeIf(pending -> pending.task == task);
            deferredMainSteps.remove(task.key);
            fail(task, new IllegalStateException("Async continuation made no tick-fence progress"));
        }
    }

    private void signalProgress() {
        progress.incrementAndGet();
        synchronized (progressMonitor) {
            progressMonitor.notifyAll();
        }
    }

    private boolean hasTaskFor(long gameTime) {
        return tasks.values().stream().anyMatch(task -> task.key.gameTime() == gameTime
                && !task.cancelled && !task.finished);
    }

    private boolean hasActiveWorkerFor(long gameTime) {
        return tasks.values().stream().anyMatch(task -> task.key.gameTime() == gameTime
                && task.activeWorkers.get() > 0);
    }

    private boolean hasDeferredMainStepFor(long gameTime) {
        return deferredMainSteps.values().stream().anyMatch(pending -> pending.task.key.gameTime() == gameTime
                && !pending.task.cancelled);
    }

    public record TaskKey(BlockPos controllerPos, long gameTime, MachineWorkMode workMode, String laneId) {
        public TaskKey(BlockPos controllerPos, long gameTime) {
            this(controllerPos, gameTime, MachineWorkMode.ASYNC, "base");
        }

        public TaskKey(BlockPos controllerPos, long gameTime, MachineWorkMode workMode) {
            this(controllerPos, gameTime, workMode, "base");
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
        private final AtomicInteger activeWorkers = new AtomicInteger();

        private Task(TaskKey key) {
            this.key = key;
        }
    }

    private record PendingMainStep(Task task, MainThreadStep step,
                                    java.util.function.Function<MainThreadStep.Result, AsyncContinuation> resume) {
    }

    @FunctionalInterface
    public interface MainThreadStepExecutor {
        MainThreadStep.Result execute(TaskKey key, MainThreadStep step);
    }
}
