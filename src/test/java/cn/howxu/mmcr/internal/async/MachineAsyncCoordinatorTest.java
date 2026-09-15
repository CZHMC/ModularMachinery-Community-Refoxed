package cn.howxu.mmcr.internal.async;

import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import cn.howxu.mmcr.internal.multiblock.SharedIoCoordinator;
import cn.howxu.mmcr.internal.multiblock.StructureClaimRegistry;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MachineAsyncCoordinatorTest {

    @Test
    void continuation_receives_the_work_mode_from_its_task_key() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L, MachineWorkMode.SEMI_SYNC);

        assertThat(coordinator.submit(key, context -> {
            assertThat(context.workMode()).isEqualTo(MachineWorkMode.SEMI_SYNC);
            return AsyncContinuation.Yield.complete();
        })).isTrue();
    }

    @Test
    void yielded_main_step_runs_on_the_pump_thread_and_resumes_the_worker_continuation() {
        List<String> phases = new CopyOnWriteArrayList<>();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L);

        coordinator.submit(key, context -> {
            phases.add("worker-before");
            return AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("main")),
                    ignored -> resumeContext -> {
                        phases.add("worker-after");
                        return AsyncContinuation.Yield.complete();
                    });
        });

        coordinator.pumpMainThreadSteps();
        coordinator.completeTick();

        assertThat(phases).containsExactly("worker-before", "main", "worker-after");
    }

    @Test
    void deferred_main_step_resumes_only_after_the_shared_io_grant() {
        List<String> phases = new CopyOnWriteArrayList<>();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L);

        coordinator.submit(key, context -> {
            phases.add("worker-before");
            return AsyncContinuation.Yield.mainThread(new MainThreadStep.Deferred(
                    MainThreadStep.Kind.BEFORE_START, () -> phases.add("main")), result -> resumeContext -> {
                phases.add("worker-after");
                return AsyncContinuation.Yield.complete();
            });
        });

        coordinator.pumpMainThreadSteps();

        assertThat(phases).containsExactly("worker-before", "main");
        coordinator.resume(key);
        coordinator.completeTick();

        assertThat(phases).containsExactly("worker-before", "main", "worker-after");
    }

    @Test
    void resumed_continuation_receives_the_work_mode_from_its_task_key() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L, MachineWorkMode.SEMI_SYNC);

        coordinator.submit(key, ignored -> AsyncContinuation.Yield.mainThread(MainThreadStep.Result::success,
                result -> context -> {
                    assertThat(context.workMode()).isEqualTo(MachineWorkMode.SEMI_SYNC);
                    return AsyncContinuation.Yield.complete();
                }));

        coordinator.pumpMainThreadSteps();
        coordinator.completeTick();
    }

    @Test
    void only_one_pending_chain_is_allowed_for_a_controller_in_one_tick() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L);

        assertThat(coordinator.submit(key, ignored -> AsyncContinuation.Yield.complete())).isTrue();
        assertThat(coordinator.submit(key, ignored -> AsyncContinuation.Yield.complete())).isFalse();
    }

    @Test
    void worker_saturation_queues_a_tick_task_without_rejecting_it() {
        ManualExecutor executor = new ManualExecutor();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(executor, 1);
        var running = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L);
        var rejected = new MachineAsyncCoordinator.TaskKey(new BlockPos(1, 0, 0), 7L);

        assertThat(coordinator.submitDetailed(running, ignored -> AsyncContinuation.Yield.complete(), null))
                .isEqualTo(MachineAsyncCoordinator.SubmissionResult.ACCEPTED);
        assertThat(coordinator.submitDetailed(rejected, ignored -> AsyncContinuation.Yield.complete(), null))
                .isEqualTo(MachineAsyncCoordinator.SubmissionResult.ACCEPTED);
        assertThat(executor.pendingTaskCount()).isEqualTo(2);
    }

    @Test
    void separate_lanes_of_one_controller_can_wait_for_their_own_shared_io_grants() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var base = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L, MachineWorkMode.ASYNC, "base");
        var factory = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L, MachineWorkMode.ASYNC, "factory-0");

        assertThat(coordinator.submit(base, ignored -> AsyncContinuation.Yield.complete())).isTrue();
        assertThat(coordinator.submit(factory, ignored -> AsyncContinuation.Yield.complete())).isTrue();
    }

    @Test
    void complete_tick_drains_all_main_steps_yielded_by_its_tick() {
        List<String> phases = new CopyOnWriteArrayList<>();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);

        coordinator.submit(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("first-main")), result -> context ->
                        AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("second-main")),
                                ignoredAgain -> contextAgain -> {
                                    phases.add("complete");
                                    return AsyncContinuation.Yield.complete();
                                })));

        coordinator.completeTick();

        assertThat(phases).containsExactly("first-main", "second-main", "complete");
    }

    @Test
    void complete_tick_does_not_run_a_later_tick() {
        List<String> phases = new CopyOnWriteArrayList<>();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);

        coordinator.submit(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("current")),
                        result -> context -> AsyncContinuation.Yield.complete()));
        coordinator.submit(new MachineAsyncCoordinator.TaskKey(new BlockPos(1, 0, 0), 41L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("later")),
                        result -> context -> AsyncContinuation.Yield.complete()));

        coordinator.completeTick();

        assertThat(phases).containsExactly("current");
    }

    @Test
    void cancelling_a_controller_discards_its_uncommitted_main_step() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        AtomicBoolean committed = new AtomicBoolean();
        coordinator.submit(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> committed.set(true)),
                        result -> context -> AsyncContinuation.Yield.complete()));

        coordinator.cancel(BlockPos.ZERO);
        coordinator.pumpMainThreadSteps();

        assertThat(committed).isFalse();
    }

    @Test
    void cancellation_wins_when_it_interleaves_after_a_main_step_is_dequeued() throws InterruptedException {
        CountDownLatch dequeued = new CountDownLatch(1);
        CountDownLatch allowPump = new CountDownLatch(1);
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run, () -> {
            dequeued.countDown();
            await(allowPump);
        });
        AtomicBoolean committed = new AtomicBoolean();
        coordinator.submit(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> committed.set(true)),
                        result -> context -> AsyncContinuation.Yield.complete()));

        Thread pump = new Thread(coordinator::pumpMainThreadSteps);
        pump.start();
        assertThat(dequeued.await(1, TimeUnit.SECONDS)).isTrue();

        coordinator.cancel(BlockPos.ZERO);
        allowPump.countDown();
        pump.join(1_000L);

        assertThat(pump.isAlive()).isFalse();
        assertThat(committed).isFalse();
    }

    @Test
    void complete_tick_waits_for_a_worker_resumed_after_a_main_step() throws InterruptedException {
        ManualExecutor executor = new ManualExecutor();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(executor);
        AtomicBoolean resumed = new AtomicBoolean();
        coordinator.submit(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L), ignored ->
                AsyncContinuation.Yield.mainThread(MainThreadStep.Result::success, result -> context -> {
                    resumed.set(true);
                    return AsyncContinuation.Yield.complete();
                }));
        executor.runNext();

        Thread completeTick = new Thread(coordinator::completeTick);
        completeTick.start();
        assertThat(executor.awaitTask()).isTrue();
        assertThat(completeTick.isAlive()).isTrue();

        executor.runNext();
        completeTick.join(1_000L);

        assertThat(completeTick.isAlive()).isFalse();
        assertThat(resumed).isTrue();
    }

    @Test
    void tick_fence_cancels_a_worker_that_makes_no_observable_progress() {
        ManualExecutor executor = new ManualExecutor();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(executor);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L);
        coordinator.submit(key, ignored -> AsyncContinuation.Yield.complete());

        assertThatCode(coordinator::completeTick).doesNotThrowAnyException();
        assertThat(coordinator.failureFor(key)).isInstanceOf(MainThreadStep.Result.Failure.class);
        MainThreadStep.Result.Failure failure = (MainThreadStep.Result.Failure) coordinator.failureFor(key);
        assertThat(failure.cause()).hasMessage("Async continuation made no tick-fence progress");
    }

    @Test
    void tick_fence_keeps_a_deferred_shared_io_request_for_the_next_level_tick() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L);
        AtomicInteger resolutions = new AtomicInteger();
        coordinator.submit(key, ignored -> AsyncContinuation.Yield.mainThread(
                new MainThreadStep.SharedIoRequest(MainThreadStep.Kind.INTENT_COMMIT, "base", 1L),
                result -> context -> AsyncContinuation.Yield.complete()));

        coordinator.completeTick(() -> {
            resolutions.incrementAndGet();
            return 0;
        });

        assertThat(resolutions.get()).isPositive();
        assertThat(coordinator.failureFor(key)).isNull();
    }

    @Test
    void deferred_shared_io_from_an_older_tick_does_not_block_newer_runnable_main_steps() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        AtomicBoolean newerStepRan = new AtomicBoolean();
        coordinator.submit(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.SharedIoRequest(
                        MainThreadStep.Kind.INTENT_COMMIT, "base", 1L), result -> context ->
                        AsyncContinuation.Yield.complete()));
        coordinator.submit(new MachineAsyncCoordinator.TaskKey(new BlockPos(1, 0, 0), 8L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> newerStepRan.set(true)),
                        result -> context -> AsyncContinuation.Yield.complete()));

        coordinator.completeTick(() -> 0);

        assertThat(newerStepRan).isTrue();
    }

    @Test
    void worker_exceptions_are_captured_without_escaping_the_pump() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L);
        coordinator.submit(key, ignored -> {
            throw new IllegalStateException("worker failure");
        });

        assertThatCode(coordinator::completeTick).doesNotThrowAnyException();
        assertThat(coordinator.failureFor(key)).isInstanceOf(MainThreadStep.Result.Failure.class);
        MainThreadStep.Result.Failure failure = (MainThreadStep.Result.Failure) coordinator.failureFor(key);
        assertThat(failure.cause()).isInstanceOf(IllegalStateException.class).hasMessage("worker failure");
    }

    @Test
    void failed_main_step_does_not_resume_its_worker_continuation() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L);
        AtomicBoolean resumed = new AtomicBoolean();

        coordinator.submit(key, ignored -> AsyncContinuation.Yield.mainThread(MainThreadStep.Result::success,
                result -> context -> {
                    resumed.set(true);
                    return AsyncContinuation.Yield.complete();
                }), (taskKey, step) -> MainThreadStep.Result.failure(new IllegalStateException("stale runtime")));

        coordinator.completeTick();

        assertThat(resumed).isFalse();
        assertThat(coordinator.failureFor(key)).isInstanceOf(MainThreadStep.Result.Failure.class);
    }

    @Test
    void tick_fence_resolves_intents_yielded_after_an_earlier_main_step() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        SharedIoCoordinator sharedIo = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = new StructureClaimRegistry.ResourceDomain(1L, 1L, Set.of(BlockPos.ZERO));
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L);
        AtomicInteger committed = new AtomicInteger();

        coordinator.submit(key, ignored -> AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> { }),
                result -> context -> AsyncContinuation.Yield.mainThread(new MainThreadStep.IntentCommit("base", 1L,
                                new cn.howxu.mmcr.internal.recipe.AsyncRequirementPlanner.PlanResult(List.of(), List.of())),
                        ignoredAgain -> finalContext -> AsyncContinuation.Yield.complete())), (taskKey, step) -> {
            if (step instanceof MainThreadStep.IntentCommit) {
                sharedIo.enqueue(new SharedIoCoordinator.TickRequest(domain,
                        new SharedIoCoordinator.LaneKey(BlockPos.ZERO, "base"), 1L, 0L,
                        () -> {
                            committed.incrementAndGet();
                            coordinator.resume(taskKey);
                            return true;
                        }, () -> true, () -> 1L, () -> 0L));
                return MainThreadStep.Result.pending();
            }
            return step.execute();
        });

        coordinator.completeTick(() -> sharedIo.resolve(domain));

        assertThat(committed).hasValue(1);
        assertThat(coordinator.failureFor(key)).isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test interleaving");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test interleaving", exception);
        }
    }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final java.util.concurrent.BlockingQueue<Runnable> tasks = new java.util.concurrent.LinkedBlockingQueue<>();
        private final java.util.concurrent.Semaphore available = new java.util.concurrent.Semaphore(0);

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
            available.release();
        }

        boolean awaitTask() throws InterruptedException {
            if (!available.tryAcquire(1, TimeUnit.SECONDS)) {
                return false;
            }
            available.release();
            return true;
        }

        void runNext() {
            if (!available.tryAcquire()) {
                throw new AssertionError("Expected a queued worker task");
            }
            Runnable task = tasks.poll();
            if (task == null) {
                throw new AssertionError("Expected a queued worker task");
            }
            task.run();
        }

        int pendingTaskCount() {
            return tasks.size();
        }
    }
}
