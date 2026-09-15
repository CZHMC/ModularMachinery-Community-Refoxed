package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityPlanner;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityRequest;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.internal.async.AsyncContinuation;
import cn.howxu.mmcr.internal.async.AsyncExecutionContext;
import cn.howxu.mmcr.internal.async.MachineAsyncCoordinator;
import cn.howxu.mmcr.internal.async.MainThreadStep;
import cn.howxu.mmcr.internal.recipe.AsyncRequirementPlanner;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the worker/main-thread boundary for async recipe planning.
 *
 * @author howxu <dev@howxu.cn>
 */
class AsyncCraftingExecutionTest {
    @Test
    void worker_plans_native_inputs_before_yielding_an_intent_commit_step() {
        AsyncRequirementPlanner.PreparedPlan prepared = new AsyncRequirementPlanner.PreparedPlan(List.of(
                new AsyncRequirementPlanner.Requirement(0, 4L, List.of(
                        new AsyncCapabilityRequest.Scalar(MMCR.id("energy"), 1L, 4L, false)))), List.of(
                new AsyncRequirementPlanner.Capability(new AsyncCapabilityPlanner.Scalar(MMCR.id("energy")),
                        new AsyncCapabilitySnapshot.Scalar(MMCR.id("energy"), 4L, 4L, 4L), Set.of())), List.of());

        AsyncCraftingExecution execution = AsyncCraftingExecution.plan(prepared, "base");
        AsyncContinuation.Yield yield = execution.advance(new AsyncExecutionContext(
                new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));

        assertThat(yield).isInstanceOf(AsyncContinuation.Yield.MainThread.class);
        assertThat(((AsyncContinuation.Yield.MainThread) yield).step().kind())
                .isEqualTo(MainThreadStep.Kind.INTENT_COMMIT);
        assertThat(execution.workerPlannedOperationCount()).isEqualTo(1);
    }

    @Test
    void unsupported_requirements_are_yielded_before_the_intent_commit() {
        AsyncRequirementPlanner.PreparedPlan prepared = new AsyncRequirementPlanner.PreparedPlan(List.of(), List.of(),
                List.of(3));
        AsyncCraftingExecution execution = AsyncCraftingExecution.plan(prepared, "base");

        AsyncContinuation.Yield first = execution.advance(new AsyncExecutionContext(
                new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));
        assertThat(((AsyncContinuation.Yield.MainThread) first).step())
                .isInstanceOf(MainThreadStep.UnsupportedRequirement.class);
        MainThreadStep.UnsupportedRequirement unsupported =
                (MainThreadStep.UnsupportedRequirement) ((AsyncContinuation.Yield.MainThread) first).step();
        assertThat(unsupported.requirementIndex()).isEqualTo(3);
        assertThat(unsupported.intent()).isNotNull();
    }

    @Test
    void start_yields_the_shared_io_reservation_without_completing_the_worker() {
        AsyncCraftingExecution execution = AsyncCraftingExecution.start("base", 1L);

        AsyncContinuation.Yield first = execution.advance(new AsyncExecutionContext(
                new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));
        AsyncContinuation.Yield flush = ((AsyncContinuation.Yield.MainThread) first).resume()
                .apply(MainThreadStep.Result.success()).advance(new AsyncExecutionContext(
                        new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));
        AsyncContinuation.Yield yield = ((AsyncContinuation.Yield.MainThread) flush).resume()
                .apply(MainThreadStep.Result.success()).advance(new AsyncExecutionContext(
                        new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));

        assertThat(((AsyncContinuation.Yield.MainThread) first).step())
                .isEqualTo(new MainThreadStep.Lifecycle(MainThreadStep.Kind.BEFORE_START, "base", 1L));
        assertThat(((AsyncContinuation.Yield.MainThread) flush).step().kind())
                .isEqualTo(MainThreadStep.Kind.SCREEN_TEXT_FLUSH);
        AsyncContinuation.Yield.MainThread mainThread = (AsyncContinuation.Yield.MainThread) yield;
        assertThat(mainThread.step().kind()).isEqualTo(MainThreadStep.Kind.BEFORE_START);
        assertThat(mainThread.step().execute()).isInstanceOf(MainThreadStep.Result.Pending.class);
    }

    @Test
    void finish_yields_the_behavior_callback_before_shared_io_commit() {
        AsyncCraftingExecution execution = AsyncCraftingExecution.finish("base", 1L);

        AsyncContinuation.Yield first = execution.advance(new AsyncExecutionContext(
                new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));

        assertThat(((AsyncContinuation.Yield.MainThread) first).step())
                .isEqualTo(new MainThreadStep.Lifecycle(MainThreadStep.Kind.BEFORE_FINISH, "base", 1L));
    }

    @Test
    void tick_yields_main_thread_preparation_before_worker_planning() {
        AsyncCraftingExecution execution = AsyncCraftingExecution.tick("base", 1L);

        AsyncContinuation.Yield yield = execution.advance(new AsyncExecutionContext(
                new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));

        assertThat(((AsyncContinuation.Yield.MainThread) yield).step())
                .isEqualTo(new MainThreadStep.Lifecycle(MainThreadStep.Kind.RECIPE_TICK, "base", 1L));
    }

    @Test
    void tick_yields_a_capability_tick_step_after_main_thread_preparation() {
        AsyncCraftingExecution execution = AsyncCraftingExecution.tick("base", 1L);
        AsyncContinuation.Yield first = execution.advance(new AsyncExecutionContext(
                new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));
        AsyncRequirementPlanner.PreparedPlan prepared = new AsyncRequirementPlanner.PreparedPlan(List.of(), List.of(), List.of());

        AsyncContinuation.Yield next = ((AsyncContinuation.Yield.MainThread) first).resume()
                .apply(MainThreadStep.Result.value(prepared)).advance(new AsyncExecutionContext(
                        new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));

        assertThat(((AsyncContinuation.Yield.MainThread) next).step().kind())
                .isEqualTo(MainThreadStep.Kind.CAPABILITY_TICK);
    }

    @Test
    void start_yields_a_screen_flush_step_before_shared_io_request() {
        AsyncCraftingExecution execution = AsyncCraftingExecution.start("base", 1L);
        AsyncContinuation.Yield first = execution.advance(new AsyncExecutionContext(
                new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));

        AsyncContinuation.Yield next = ((AsyncContinuation.Yield.MainThread) first).resume()
                .apply(MainThreadStep.Result.success()).advance(new AsyncExecutionContext(
                        new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));

        assertThat(((AsyncContinuation.Yield.MainThread) next).step().kind())
                .isEqualTo(MainThreadStep.Kind.SCREEN_TEXT_FLUSH);
    }

    @Test
    void worker_result_is_a_pure_intent_commit_step() {
        AsyncRequirementPlanner.PreparedPlan prepared = new AsyncRequirementPlanner.PreparedPlan(List.of(), List.of(), List.of());
        AsyncCraftingExecution execution = AsyncCraftingExecution.plan(prepared, "factory-0", 7L);

        AsyncContinuation.Yield yield = execution.advance(new AsyncExecutionContext(
                new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L, MachineWorkMode.ASYNC, "factory-0")));

        AsyncContinuation.Yield.MainThread mainThread = (AsyncContinuation.Yield.MainThread) yield;
        assertThat(mainThread.step()).isInstanceOf(MainThreadStep.IntentCommit.class);
        assertThat(((MainThreadStep.IntentCommit) mainThread.step()).laneId()).isEqualTo("factory-0");
        assertThat(((MainThreadStep.IntentCommit) mainThread.step()).catalogVersion()).isEqualTo(7L);
    }
}
