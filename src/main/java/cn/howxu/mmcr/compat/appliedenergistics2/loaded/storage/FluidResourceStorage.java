package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.helpers.externalstorage.GenericStackInv;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2KeyAdapter;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * Fluid-resource view over an AE2 generic stack inventory.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class FluidResourceStorage extends ResourceStorage<FluidResource> {
    public FluidResourceStorage(GenericStackInv inventory) {
        super(inventory, adapter());
    }

    public static AE2KeyAdapter<FluidResource> adapter() {
        return AE2ResourceFamilies.FLUID.adapter();
    }
}
