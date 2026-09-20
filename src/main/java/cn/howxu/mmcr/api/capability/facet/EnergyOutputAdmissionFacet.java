package cn.howxu.mmcr.api.capability.facet;

import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import org.jetbrains.annotations.Nullable;

/**
 * Plans energy output admission through a capability-owned destination.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface EnergyOutputAdmissionFacet extends CapabilityFacet {
    /**
     * Returns the amount currently available after applying the supplied planning reservations.
     * This query must not commit or mutate the caller-owned transaction.
     *
     * @param reservations shared reservations for the current planning pass
     * @return output amount available to this destination
     * @author howxu <dev@howxu.cn>
     */
    long outputCapacity(PlanningReservations reservations);

    /**
     * Reserves output capacity and optionally creates the operation that materializes it.
     * Implementations must use the supplied reservations; {@code materialize} is false during
     * candidate planning and true when the selected plan needs an executable operation.
     *
     * @param requestedAmount amount the recipe wants to output
     * @param reservations shared reservations for the current planning pass
     * @param materialize whether to return an operation for the selected reservation
     * @return accepted amount and an optional operation, without committing a transaction
     * @author howxu <dev@howxu.cn>
     */
    OutputPlan planOutput(long requestedAmount, PlanningReservations reservations, boolean materialize);

    /**
     * Accepted output and an optional operation for materialized plans.
     *
     * @param accepted amount admitted by the destination
     * @param operation optional operation for applying the admission
     * @author howxu <dev@howxu.cn>
     */
    record OutputPlan(long accepted, @Nullable CapabilityOperation operation) {
        public OutputPlan {
            if (accepted < 0L) throw new IllegalArgumentException("accepted must be non-negative");
        }
    }
}
