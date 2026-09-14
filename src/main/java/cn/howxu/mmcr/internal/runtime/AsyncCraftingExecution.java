package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.internal.async.AsyncContinuation;
import cn.howxu.mmcr.internal.async.AsyncExecutionContext;
import cn.howxu.mmcr.internal.async.MainThreadStep;
import cn.howxu.mmcr.internal.recipe.AsyncRequirementPlanner;
import cn.howxu.mmcr.internal.recipe.RecipeThread;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Worker-side recipe planning state. It contains only immutable planning values and a lane id.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AsyncCraftingExecution implements AsyncContinuation {
    private final AsyncRequirementPlanner.PreparedPlan preparedPlan;
    private final String laneId;
    private final Consumer<AsyncRequirementPlanner.PlanResult> intentCommit;
    private AsyncRequirementPlanner.PlanResult planResult = new AsyncRequirementPlanner.PlanResult(java.util.List.of(),
            java.util.List.of());
    private boolean planned;
    private int nextMainThreadRequirement;
    private boolean intentCommitYielded;

    private AsyncCraftingExecution(AsyncRequirementPlanner.PreparedPlan preparedPlan, String laneId,
                                   Consumer<AsyncRequirementPlanner.PlanResult> intentCommit) {
        this.preparedPlan = Objects.requireNonNull(preparedPlan, "preparedPlan");
        this.laneId = Objects.requireNonNull(laneId, "laneId");
        this.intentCommit = Objects.requireNonNull(intentCommit, "intentCommit");
    }

    public static AsyncCraftingExecution plan(AsyncRequirementPlanner.PreparedPlan preparedPlan, String laneId) {
        return new AsyncCraftingExecution(preparedPlan, laneId, ignored -> { });
    }

    /** Creates a tick continuation; the lane is accessed only by the yielded main-thread commit step. */
    public static AsyncContinuation tick(RecipeThread lane, AsyncRequirementPlanner.PreparedPlan preparedPlan) {
        Objects.requireNonNull(lane, "lane");
        return new AsyncCraftingExecution(preparedPlan, lane.asyncLaneId(), lane::completeAsyncTick);
    }

    @Override
    public AsyncContinuation.Yield advance(AsyncExecutionContext context) {
        if (!planned) {
            planResult = preparedPlan.plan();
            planned = true;
        }
        if (nextMainThreadRequirement < planResult.mainThreadRequirements().size()) {
            nextMainThreadRequirement++;
            return AsyncContinuation.Yield.mainThread(
                    new MainThreadStep.Named(MainThreadStep.Kind.UNSUPPORTED_REQUIREMENT, () -> { }),
                    ignored -> this);
        }
        if (intentCommitYielded) return AsyncContinuation.Yield.complete();
        intentCommitYielded = true;
        return AsyncContinuation.Yield.mainThread(new MainThreadStep.Named(MainThreadStep.Kind.INTENT_COMMIT,
                        () -> intentCommit.accept(planResult)),
                ignored -> ignoredContext -> AsyncContinuation.Yield.complete());
    }

    public int workerPlannedOperationCount() {
        return planResult.operations().size();
    }

    public String laneId() {
        return laneId;
    }
}
