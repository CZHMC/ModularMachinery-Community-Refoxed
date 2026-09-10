package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.helpers.externalstorage.GenericStackInv;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.Optional;

/**
 * Fluid-resource view over an AE2 generic stack inventory.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2FluidResourceStorage extends AE2ResourceStorage<FluidResource> {
    private static final AE2KeyAdapter<FluidResource> ADAPTER = new AE2KeyAdapter<>() {
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
    };

    public AE2FluidResourceStorage(GenericStackInv inventory) {
        super(inventory, adapter());
    }

    public static AE2KeyAdapter<FluidResource> adapter() {
        return ADAPTER;
    }
}
