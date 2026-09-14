package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.network;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.List;

/**
 * Fluid-resource view over an AE2 network.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class FluidNetworkResourceStorage extends NetworkResourceStorage<FluidResource> {
    public FluidNetworkResourceStorage(MEStorage meStorage, List<AEKey> keys) {
        super(meStorage, keys, AE2ResourceFamilies.FLUID.adapter());
    }
}
