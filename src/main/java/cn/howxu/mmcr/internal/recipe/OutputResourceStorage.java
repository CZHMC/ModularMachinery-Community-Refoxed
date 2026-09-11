package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import org.jetbrains.annotations.Nullable;

/**
 * Resource storage contract for planning dynamic output capacity.
 *
 * @param <R> stored resource type
 * @author howxu <dev@howxu.cn>
 */
public interface OutputResourceStorage<R> extends ResourceStorage<R> {
    long outputCapacity(R resource);

    OutputPlan planOutput(R resource, long amount,
                          PlanningReservations reservations, boolean materialize);

    record OutputPlan(long accepted, @Nullable CapabilityOperation operation) {
        public OutputPlan {
            if (accepted < 0L) throw new IllegalArgumentException("accepted must be non-negative");
        }
    }
}
