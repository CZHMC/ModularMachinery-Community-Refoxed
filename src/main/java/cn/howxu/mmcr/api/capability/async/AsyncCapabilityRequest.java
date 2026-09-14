package cn.howxu.mmcr.api.capability.async;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * A worker-safe capability request for asynchronous planning.
 *
 * @author howxu <dev@howxu.cn>
 */
public sealed interface AsyncCapabilityRequest permits AsyncCapabilityRequest.Resource, AsyncCapabilityRequest.Scalar {
    Identifier capabilityId();

    long parallelism();

    /**
     * A resource request expressed as immutable resource actions.
     *
     * @param capabilityId capability type identifier
     * @param parallelism requested parallelism
     * @param actions requested resource actions
     */
    record Resource(Identifier capabilityId, long parallelism, List<AsyncResourceAction> actions)
            implements AsyncCapabilityRequest {
        public Resource {
            Objects.requireNonNull(capabilityId, "capabilityId");
            if (parallelism <= 0L) throw new IllegalArgumentException("parallelism must be positive");
            actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        }
    }

    /**
     * A scalar capability request, such as energy.
     *
     * @param capabilityId capability type identifier
     * @param parallelism requested parallelism
     * @param amount requested scalar amount
     * @param insert whether the scalar is inserted rather than extracted
     */
    record Scalar(Identifier capabilityId, long parallelism, long amount, boolean insert) implements AsyncCapabilityRequest {
        public Scalar {
            Objects.requireNonNull(capabilityId, "capabilityId");
            if (parallelism <= 0L || amount <= 0L) {
                throw new IllegalArgumentException("parallelism and amount must be positive");
            }
        }
    }
}
