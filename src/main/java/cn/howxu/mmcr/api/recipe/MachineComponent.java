package cn.howxu.mmcr.api.recipe;

import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.util.IOType;

/**
 * @author howxu <dev@howxu.cn>
 */
public record MachineComponent(IOPortKind kind, CapabilityDirections directions) {
    public MachineComponent(IOPortKind kind, IOType ioType) {
        this(kind, CapabilityDirections.of(ioType));
    }
}
