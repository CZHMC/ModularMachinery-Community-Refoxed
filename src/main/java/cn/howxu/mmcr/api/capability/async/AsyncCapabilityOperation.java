package cn.howxu.mmcr.api.capability.async;

import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * An immutable capability operation that may be planned off the main thread.
 *
 * @author howxu <dev@howxu.cn>
 */
public sealed interface AsyncCapabilityOperation permits AsyncCapabilityOperation.Resource, AsyncCapabilityOperation.Scalar {
    Identifier capabilityId();

    /**
     * A resource insertion or extraction for one storage slot.
     *
     * @param capabilityId capability type identifier
     * @param slot storage slot index
     * @param resource immutable resource value
     * @param amount resource amount
     * @param insert whether the resource is inserted rather than extracted
     */
    record Resource(Identifier capabilityId, int slot, AsyncResourceValue resource, long amount, boolean insert)
            implements AsyncCapabilityOperation {
        public Resource {
            if (slot < 0 || amount <= 0L) throw new IllegalArgumentException("slot and amount must be positive");
            Objects.requireNonNull(capabilityId, "capabilityId");
            Objects.requireNonNull(resource, "resource");
        }
    }

    /**
     * A scalar insertion or extraction, such as energy.
     *
     * @param capabilityId capability type identifier
     * @param amount scalar amount
     * @param insert whether the scalar is inserted rather than extracted
     */
    record Scalar(Identifier capabilityId, long amount, boolean insert) implements AsyncCapabilityOperation {
        public Scalar {
            Objects.requireNonNull(capabilityId, "capabilityId");
            if (amount <= 0L) throw new IllegalArgumentException("amount must be positive");
        }
    }
}
