package cn.howxu.mmcr.api.capability.async;

import java.util.Optional;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * Plans a logical capability operation from immutable values on a worker thread.
 *
 * @author howxu <dev@howxu.cn>
 */
public sealed interface AsyncCapabilityPlanner permits AsyncCapabilityPlanner.Resource, AsyncCapabilityPlanner.Scalar {
    /**
     * Produces an operation supported by the captured snapshot and request.
     *
     * @param snapshot immutable capability values captured on the server thread
     * @param request immutable requested capability operation
     * @return a logical operation when the request is supported
     */
    Optional<AsyncCapabilityOperation> plan(AsyncCapabilitySnapshot snapshot, AsyncCapabilityRequest request);

    /**
     * A pure planner for slot-based resource capabilities.
     *
     * @param capabilityId capability type identifier
     */
    record Resource(Identifier capabilityId) implements AsyncCapabilityPlanner {
        public Resource {
            Objects.requireNonNull(capabilityId, "capabilityId");
        }

        @Override
        public Optional<AsyncCapabilityOperation> plan(AsyncCapabilitySnapshot snapshot, AsyncCapabilityRequest request) {
            if (!(snapshot instanceof AsyncCapabilitySnapshot.Resource resourceSnapshot)
                    || !(request instanceof AsyncCapabilityRequest.Resource resourceRequest)
                    || !capabilityId.equals(resourceSnapshot.capabilityId())
                    || !capabilityId.equals(resourceRequest.capabilityId())
                    || resourceRequest.actions().size() != 1) {
                return Optional.empty();
            }

            AsyncResourceAction action = resourceRequest.actions().getFirst();
            for (int slot = 0; slot < resourceSnapshot.slots().size(); slot++) {
                AsyncCapabilitySnapshot.ResourceSlot stored = resourceSnapshot.slots().get(slot);
                if (action.insert() && (stored.resource().isEmpty() || stored.resource().get().equals(action.resource()))) {
                    long amount = Math.min(action.amount(), stored.capacity() - stored.amount());
                    if (amount > 0L) {
                        return Optional.of(new AsyncCapabilityOperation.Resource(capabilityId, slot, action.resource(), amount, true));
                    }
                } else if (!action.insert() && stored.resource().filter(action.resource()::equals).isPresent()) {
                    long amount = Math.min(action.amount(), stored.amount());
                    if (amount > 0L) {
                        return Optional.of(new AsyncCapabilityOperation.Resource(capabilityId, slot, action.resource(), amount, false));
                    }
                }
            }
            return Optional.empty();
        }
    }

    /**
     * A pure planner for scalar capabilities such as energy.
     *
     * @param capabilityId capability type identifier
     */
    record Scalar(Identifier capabilityId) implements AsyncCapabilityPlanner {
        public Scalar {
            Objects.requireNonNull(capabilityId, "capabilityId");
        }

        @Override
        public Optional<AsyncCapabilityOperation> plan(AsyncCapabilitySnapshot snapshot, AsyncCapabilityRequest request) {
            if (!(snapshot instanceof AsyncCapabilitySnapshot.Scalar scalarSnapshot)
                    || !(request instanceof AsyncCapabilityRequest.Scalar scalarRequest)
                    || !capabilityId.equals(scalarSnapshot.capabilityId())
                    || !capabilityId.equals(scalarRequest.capabilityId())) {
                return Optional.empty();
            }

            long available = scalarRequest.insert()
                    ? scalarSnapshot.capacity() - scalarSnapshot.amount()
                    : scalarSnapshot.amount();
            long amount = Math.min(scalarRequest.amount(), available);
            return amount > 0L
                    ? Optional.of(new AsyncCapabilityOperation.Scalar(capabilityId, amount, scalarRequest.insert()))
                    : Optional.empty();
        }
    }
}
