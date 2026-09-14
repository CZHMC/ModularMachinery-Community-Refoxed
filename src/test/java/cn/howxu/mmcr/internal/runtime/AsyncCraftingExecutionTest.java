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
        AsyncContinuation.Yield second = ((AsyncContinuation.Yield.MainThread) first).resume()
                .apply(MainThreadStep.Result.success()).advance(new AsyncExecutionContext(
                        new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 1L)));

        assertThat(((AsyncContinuation.Yield.MainThread) first).step().kind())
                .isEqualTo(MainThreadStep.Kind.UNSUPPORTED_REQUIREMENT);
        assertThat(((AsyncContinuation.Yield.MainThread) second).step().kind())
                .isEqualTo(MainThreadStep.Kind.INTENT_COMMIT);
    }
}
