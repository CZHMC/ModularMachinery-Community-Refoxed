package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.internal.async.AsyncContinuation;
import cn.howxu.mmcr.internal.async.AsyncExecutionContext;
import cn.howxu.mmcr.internal.async.MainThreadStep;
import cn.howxu.mmcr.internal.recipe.AsyncRequirementPlanner;

import java.util.Objects;

/**
 * Worker-side recipe planning state. It contains only immutable planning values and a lane id.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AsyncCraftingExecution implements AsyncContinuation {
    private final AsyncRequirementPlanner.PreparedPlan preparedPlan;
    private final String laneId;
    private final MainThreadStep.Kind sharedIoRequest;
    private final long catalogVersion;
    private AsyncRequirementPlanner.PlanResult planResult = new AsyncRequirementPlanner.PlanResult(java.util.List.of(),
            java.util.List.of());
    private boolean planned;
    private int nextMainThreadRequirement;
    private boolean intentCommitYielded;

    private AsyncCraftingExecution(AsyncRequirementPlanner.PreparedPlan preparedPlan, String laneId, long catalogVersion) {
        this.preparedPlan = Objects.requireNonNull(preparedPlan, "preparedPlan");
        this.laneId = Objects.requireNonNull(laneId, "laneId");
        this.sharedIoRequest = null;
        this.catalogVersion = catalogVersion;
    }

    private AsyncCraftingExecution(String laneId, MainThreadStep.Kind sharedIoRequest, long catalogVersion) {
        this.preparedPlan = null;
        this.laneId = Objects.requireNonNull(laneId, "laneId");
        this.sharedIoRequest = Objects.requireNonNull(sharedIoRequest, "sharedIoRequest");
        this.catalogVersion = catalogVersion;
    }

    public static AsyncCraftingExecution plan(AsyncRequirementPlanner.PreparedPlan preparedPlan, String laneId) {
        return plan(preparedPlan, laneId, Long.MIN_VALUE);
    }

    public static AsyncCraftingExecution plan(AsyncRequirementPlanner.PreparedPlan preparedPlan, String laneId,
                                              long catalogVersion) {
        return new AsyncCraftingExecution(preparedPlan, laneId, catalogVersion);
    }

    /** Defers shared-IO start arbitration to the server thread and awaits its coordinator grant. */
    public static AsyncCraftingExecution start(String laneId, long catalogVersion) {
        return new AsyncCraftingExecution(laneId, MainThreadStep.Kind.BEFORE_START, catalogVersion);
    }

    public static AsyncCraftingExecution finish(String laneId, long catalogVersion) {
        return new AsyncCraftingExecution(laneId, MainThreadStep.Kind.BEFORE_FINISH, catalogVersion);
    }

    @Override
    public AsyncContinuation.Yield advance(AsyncExecutionContext context) {
        if (sharedIoRequest != null) {
            return AsyncContinuation.Yield.mainThread(new MainThreadStep.SharedIoRequest(sharedIoRequest, laneId, catalogVersion),
                    ignored -> ignoredContext -> AsyncContinuation.Yield.complete());
        }
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
        return AsyncContinuation.Yield.mainThread(new MainThreadStep.IntentCommit(laneId, catalogVersion, planResult),
                ignored -> ignoredContext -> AsyncContinuation.Yield.complete());
    }

    public int workerPlannedOperationCount() {
        return planResult.operations().size();
    }

    public String laneId() {
        return laneId;
    }
}
