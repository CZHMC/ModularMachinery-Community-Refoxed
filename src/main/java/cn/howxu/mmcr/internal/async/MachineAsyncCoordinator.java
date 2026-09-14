package cn.howxu.mmcr.internal.async;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

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

    private final Executor executor;
    private final Map<BlockPos, Task> tasksByController = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PendingMainStep> pendingMainSteps = new ConcurrentLinkedQueue<>();
    private final Map<TaskKey, MainThreadStep.Result.Failure> failures = new ConcurrentHashMap<>();

    private MachineAsyncCoordinator(Executor executor) {
        this.executor = executor;
    }

    public static synchronized MachineAsyncCoordinator get(ServerLevel level) {
        return COORDINATORS.computeIfAbsent(level, ignored -> new MachineAsyncCoordinator(WORKERS));
    }

    public static MachineAsyncCoordinator forTesting(Executor executor) {
        return new MachineAsyncCoordinator(executor);
    }

    public boolean submit(TaskKey key, AsyncContinuation continuation) {
        Task task = new Task(key);
        if (tasksByController.putIfAbsent(key.controllerPos(), task) != null) {
            return false;
        }
        schedule(task, continuation);
        return true;
    }

    public void pumpMainThreadSteps() {
        pumpMainThreadSteps(null);
    }

    public void completeTick() {
        Long gameTime = tasksByController.values().stream()
                .map(task -> task.key.gameTime())
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (gameTime == null) {
            return;
        }
        while (hasTaskFor(gameTime)) {
            pumpMainThreadSteps(gameTime);
            if (hasActiveWorkerFor(gameTime)) {
                LockSupport.parkNanos(1_000_000L);
            }
        }
        tasksByController.values().removeIf(task -> task.key.gameTime() == gameTime && task.finished);
    }

    public void cancel(BlockPos controllerPos) {
        Task task = tasksByController.remove(controllerPos);
        if (task == null) {
            return;
        }
        task.cancelled = true;
        pendingMainSteps.removeIf(pending -> pending.task == task);
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
            if (pending.task.cancelled) {
                continue;
            }
            MainThreadStep.Result result;
            try {
                result = pending.step.execute();
            } catch (Throwable throwable) {
                result = MainThreadStep.Result.failure(throwable);
            }
            scheduleResume(pending.task, pending.resume, result);
        }
    }

    private void scheduleResume(Task task, java.util.function.Function<MainThreadStep.Result, AsyncContinuation.Yield> resume,
                                MainThreadStep.Result result) {
        task.activeWorkers++;
        executor.execute(() -> {
            try {
                if (!task.cancelled) {
                    handleYield(task, resume.apply(result));
                }
            } catch (Throwable throwable) {
                fail(task, throwable);
            } finally {
                task.activeWorkers--;
            }
        });
    }

    private void schedule(Task task, AsyncContinuation continuation) {
        task.activeWorkers++;
        executor.execute(() -> {
            try {
                scheduleResult(task, continuation);
            } catch (Throwable throwable) {
                fail(task, throwable);
            } finally {
                task.activeWorkers--;
            }
        });
    }

    private void scheduleResult(Task task, AsyncContinuation continuation) {
        if (task.cancelled) {
            return;
        }
        handleYield(task, continuation.advance(new AsyncExecutionContext(task.key)));
    }

    private void handleYield(Task task, AsyncContinuation.Yield yielded) {
        if (yielded instanceof AsyncContinuation.Yield.Complete) {
            task.finished = true;
        } else if (yielded instanceof AsyncContinuation.Yield.MainThread mainThread) {
            pendingMainSteps.add(new PendingMainStep(task, mainThread.step(), mainThread.resume()));
        }
    }

    private void fail(Task task, Throwable throwable) {
        failures.put(task.key, MainThreadStep.Result.failure(throwable));
        task.finished = true;
    }

    private boolean hasTaskFor(long gameTime) {
        return tasksByController.values().stream().anyMatch(task -> task.key.gameTime() == gameTime
                && !task.cancelled && !task.finished);
    }

    private boolean hasActiveWorkerFor(long gameTime) {
        return tasksByController.values().stream().anyMatch(task -> task.key.gameTime() == gameTime && task.activeWorkers > 0);
    }

    public record TaskKey(BlockPos controllerPos, long gameTime) {
        public TaskKey {
            controllerPos = controllerPos.immutable();
        }
    }

    private static final class Task {
        private final TaskKey key;
        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile int activeWorkers;

        private Task(TaskKey key) {
            this.key = key;
        }
    }

    private record PendingMainStep(Task task, MainThreadStep step,
                                   java.util.function.Function<MainThreadStep.Result, AsyncContinuation.Yield> resume) {
    }
}
