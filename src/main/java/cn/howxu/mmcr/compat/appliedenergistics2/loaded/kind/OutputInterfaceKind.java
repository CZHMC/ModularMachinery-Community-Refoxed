package cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.capability.type.CapabilityBinding;
import cn.howxu.mmcr.api.port.PortDefinition;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBlockEntity;
import cn.howxu.mmcr.internal.capability.BuiltinCapabilityDefinitions;
import cn.howxu.mmcr.internal.capability.FluidHatchCapability;
import cn.howxu.mmcr.internal.capability.ItemBusCapability;
import cn.howxu.mmcr.internal.port.FluidHatchSize;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.port.ItemBusSize;
import cn.howxu.mmcr.internal.port.PortFamilyDescriptor;
import cn.howxu.mmcr.internal.port.PortFamilyIds;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.List;

/**
 * AE2 interface-backed output port kind with a bounded local cache.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class OutputInterfaceKind implements IOPortKind {
    private static final String ID = "ae2_me_output_interface";
    private static final List<PortFamilyDescriptor> FAMILIES = List.of(
            new PortFamilyDescriptor(PortFamilyIds.ITEM, IOType.OUTPUT, ItemBusSize.LUDICROUS.ordinal() + 1,
                    List.of("item_output_bus")),
            new PortFamilyDescriptor(PortFamilyIds.FLUID, IOType.OUTPUT, FluidHatchSize.VACUUM.ordinal() + 1,
                    List.of("fluid_output_hatch")));

    public static final OutputInterfaceKind INSTANCE = new OutputInterfaceKind();

    private final PortDefinition definition = PortDefinition.of(MMCR.id(ID), List.of(
            new CapabilityBinding(BuiltinCapabilityDefinitions.ITEM_TYPE, CapabilityDirections.of(IOType.OUTPUT),
                    context -> {
                        OutputInterfaceBlockEntity host = (OutputInterfaceBlockEntity) context.host();
                        return new ItemBusCapability(host, host.itemStorage(), IOType.OUTPUT, false);
                    },
                    (binding, tier) -> true),
            new CapabilityBinding(BuiltinCapabilityDefinitions.FLUID_TYPE, CapabilityDirections.of(IOType.OUTPUT),
                    context -> {
                        OutputInterfaceBlockEntity host = (OutputInterfaceBlockEntity) context.host();
                        return new FluidHatchCapability(host, host.fluidStorage(), IOType.OUTPUT, false);
                    },
                    (binding, tier) -> true)));

    private OutputInterfaceKind() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public IOType ioType() {
        return IOType.OUTPUT;
    }

    @Override
    public List<PortFamilyDescriptor> families() {
        return FAMILIES;
    }

    @Override
    public BlockEntityType.BlockEntitySupplier<OutputInterfaceBlockEntity> entityFactory() {
        return (pos, state) -> new OutputInterfaceBlockEntity(pos, state, this);
    }

    @Override
    public PortDefinition definition() {
        return definition;
    }
}
