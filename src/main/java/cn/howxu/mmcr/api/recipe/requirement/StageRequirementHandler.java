package cn.howxu.mmcr.api.recipe.requirement;

import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.plan.PlanningContext;
import cn.howxu.mmcr.api.capability.plan.RequirementPlan;

import java.util.List;

/**
 * @author howxu <dev@howxu.cn>
 */
public final class StageRequirementHandler implements RequirementHandler<StageRequirement> {
    @Override
    public RequirementPlan plan(StageRequirement requirement, List<MachineCapability> capabilities,
                                PlanningContext context) {
        return new RequirementPlan(context.requirementIndex(), context.requestedParallelism(), List.of(), null);
    }
}
