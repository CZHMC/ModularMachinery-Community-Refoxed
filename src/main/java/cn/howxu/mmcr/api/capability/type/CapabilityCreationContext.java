package cn.howxu.mmcr.api.capability.type;

import cn.howxu.mmcr.api.capability.CapabilityHost;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.util.IOType;

import java.util.Optional;

/**
 * Context supplied when a capability factory creates a hosted capability.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface CapabilityCreationContext {
    CapabilityHost host();

    IOType ioType();

    /**
     * Returns the complete direction declaration of the binding being created.
     * Existing factories that only need a port's physical direction can continue using {@link #ioType()}.
     */
    default CapabilityDirections directions() {
        return CapabilityDirections.of(ioType());
    }

    <T> Optional<T> service(Class<T> serviceType);

    Runnable onChanged();
}
