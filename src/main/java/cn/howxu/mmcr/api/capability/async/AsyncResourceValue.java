package cn.howxu.mmcr.api.capability.async;

import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * A worker-safe resource value encoded by a capability adapter.
 *
 * @param resourceId identifier of the resource represented by this value
 * @param data immutable canonical resource data
 * @author howxu <dev@howxu.cn>
 */
public record AsyncResourceValue(Identifier resourceId, String data) {
    public AsyncResourceValue {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(data, "data");
    }
}
