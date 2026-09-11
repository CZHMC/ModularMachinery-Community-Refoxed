package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.List;

/**
 * Fluid-resource view over an AE2 network.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2FluidNetworkResourceStorage extends AE2NetworkResourceStorage<FluidResource> {
    public AE2FluidNetworkResourceStorage(MEStorage meStorage, List<AEKey> keys) {
        super(meStorage, keys, AE2FluidResourceStorage.adapter());
    }
}
