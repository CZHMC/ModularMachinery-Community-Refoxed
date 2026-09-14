package cn.howxu.mmcr.api.capability.async;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * An immutable capability state captured on the main thread for asynchronous planning.
 *
 * @author howxu <dev@howxu.cn>
 */
public sealed interface AsyncCapabilitySnapshot permits AsyncCapabilitySnapshot.Resource {
    /**
     * A resource capability snapshot.
     *
     * @param capabilityId capability type identifier
     * @param slots immutable slot contents
     * @param <R> immutable resource value type
     */
    record Resource<R>(Identifier capabilityId, List<ResourceSlot<R>> slots) implements AsyncCapabilitySnapshot {
        public Resource {
            Objects.requireNonNull(capabilityId, "capabilityId");
            slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        }
    }

    /**
     * Immutable contents and capacity of a resource storage slot.
     *
     * @param resource resource value
     * @param amount stored amount
     * @param capacity slot capacity
     * @param <R> immutable resource value type
     */
    record ResourceSlot<R>(R resource, long amount, long capacity) {
        public ResourceSlot {
            Objects.requireNonNull(resource, "resource");
            if (amount < 0L || capacity < 0L || amount > capacity) {
                throw new IllegalArgumentException("slot amounts must be within capacity");
            }
        }
    }
}
