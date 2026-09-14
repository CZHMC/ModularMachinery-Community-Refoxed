package cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.ItemResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.FluidResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.network.FluidNetworkResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.network.ItemNetworkResourceStorage;
import cn.howxu.mmcr.internal.capability.BuiltinCapabilityDefinitions;
import cn.howxu.mmcr.internal.capability.FluidHatchCapability;
import cn.howxu.mmcr.internal.capability.ItemBusCapability;
import cn.howxu.mmcr.internal.port.FluidHatchSize;
import cn.howxu.mmcr.internal.port.ItemBusSize;
import cn.howxu.mmcr.internal.port.PortFamilyDescriptor;
import cn.howxu.mmcr.internal.port.PortFamilyIds;
import cn.howxu.mmcr.util.IOType;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;
import java.util.Optional;

/**
 * The two AE2 resource families supported by MMCR interfaces.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2ResourceFamilies {
    public static final AE2ResourceFamily<ItemResource> ITEM = new AE2ResourceFamily<>(
            BuiltinCapabilityDefinitions.ITEM_TYPE,
            new AE2KeyAdapter<>() {
                @Override
                public AEKeyType keyType() {
                    return AEKeyType.items();
                }

                @Override
                public Class<ItemResource> resourceType() {
                    return ItemResource.class;
                }

                @Override
                public AEKey toKey(ItemResource resource) {
                    return AEItemKey.of(resource);
                }

                @Override
                public Optional<ItemResource> toResource(AEKey key) {
                    return key instanceof AEItemKey itemKey
                            ? Optional.of(itemKey.toResource())
                            : Optional.empty();
                }
            },
            new PortFamilyDescriptor(PortFamilyIds.ITEM, IOType.INPUT, ItemBusSize.values().length,
                    List.of("item_input_bus")),
            new PortFamilyDescriptor(PortFamilyIds.ITEM, IOType.OUTPUT, ItemBusSize.LUDICROUS.ordinal() + 1,
                    List.of("item_output_bus")),
            ItemResourceStorage::new,
            ItemNetworkResourceStorage::new,
            ItemBusCapability::new);

    public static final AE2ResourceFamily<FluidResource> FLUID = new AE2ResourceFamily<>(
            BuiltinCapabilityDefinitions.FLUID_TYPE,
            new AE2KeyAdapter<>() {
                @Override
                public AEKeyType keyType() {
                    return AEKeyType.fluids();
                }

                @Override
                public Class<FluidResource> resourceType() {
                    return FluidResource.class;
                }

                @Override
                public AEKey toKey(FluidResource resource) {
                    return AEFluidKey.of(resource);
                }

                @Override
                public Optional<FluidResource> toResource(AEKey key) {
                    return key instanceof AEFluidKey fluidKey
                            ? Optional.of(fluidKey.toResource())
                            : Optional.empty();
                }
            },
            new PortFamilyDescriptor(PortFamilyIds.FLUID, IOType.INPUT, FluidHatchSize.values().length,
                    List.of("fluid_input_hatch")),
            new PortFamilyDescriptor(PortFamilyIds.FLUID, IOType.OUTPUT, FluidHatchSize.VACUUM.ordinal() + 1,
                    List.of("fluid_output_hatch")),
            FluidResourceStorage::new,
            FluidNetworkResourceStorage::new,
            FluidHatchCapability::new);

    private AE2ResourceFamilies() {
    }

    public static List<AE2ResourceFamily<?>> all() {
        return List.of(ITEM, FLUID);
    }

    public static List<PortFamilyDescriptor> inputFamilies() {
        return List.of(ITEM.inputFamily(), FLUID.inputFamily());
    }

    public static List<PortFamilyDescriptor> outputFamilies() {
        return List.of(ITEM.outputFamily(), FLUID.outputFamily());
    }

    public static List<PortFamilyDescriptor> patternFamilies() {
        return List.of(ITEM.inputFamily(), FLUID.inputFamily(), ITEM.outputFamily(), FLUID.outputFamily());
    }
}
