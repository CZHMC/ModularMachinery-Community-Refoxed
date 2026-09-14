package cn.howxu.mmcr.internal.async;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MachineAsyncCoordinatorTest {

    @Test
    void yielded_main_step_runs_on_the_pump_thread_and_resumes_the_worker_continuation() {
        List<String> phases = new CopyOnWriteArrayList<>();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L);

        coordinator.submit(key, context -> {
            phases.add("worker-before");
            return AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("main")),
                    ignored -> {
                        phases.add("worker-after");
                        return AsyncContinuation.Yield.complete();
                    });
        });

        coordinator.pumpMainThreadSteps();
        coordinator.completeTick();

        assertThat(phases).containsExactly("worker-before", "main", "worker-after");
    }

    @Test
    void only_one_pending_chain_is_allowed_for_a_controller_in_one_tick() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L);

        assertThat(coordinator.submit(key, ignored -> AsyncContinuation.Yield.complete())).isTrue();
        assertThat(coordinator.submit(key, ignored -> AsyncContinuation.Yield.complete())).isFalse();
    }

    @Test
    void complete_tick_drains_all_main_steps_yielded_by_its_tick() {
        List<String> phases = new CopyOnWriteArrayList<>();
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);

        coordinator.submit(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("first-main")), result ->
                        AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("second-main")),
                                ignoredAgain -> {
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
                        result -> AsyncContinuation.Yield.complete()));
        coordinator.submit(new MachineAsyncCoordinator.TaskKey(new BlockPos(1, 0, 0), 41L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> phases.add("later")),
                        result -> AsyncContinuation.Yield.complete()));

        coordinator.completeTick();

        assertThat(phases).containsExactly("current");
    }

    @Test
    void cancelling_a_controller_discards_its_uncommitted_main_step() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        AtomicBoolean committed = new AtomicBoolean();
        coordinator.submit(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 7L), ignored ->
                AsyncContinuation.Yield.mainThread(new MainThreadStep.TestStep(() -> committed.set(true)),
                        result -> AsyncContinuation.Yield.complete()));

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
                        result -> AsyncContinuation.Yield.complete()));

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
                AsyncContinuation.Yield.mainThread(MainThreadStep.Result::success, result -> {
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
    }
}
