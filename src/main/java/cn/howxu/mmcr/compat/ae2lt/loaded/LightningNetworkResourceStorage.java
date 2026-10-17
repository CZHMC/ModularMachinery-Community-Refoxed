package cn.howxu.mmcr.compat.ae2lt.loaded;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.api.compat.ae2lt.LightningTier;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.network.NetworkResourceStorage;

import java.util.List;

/** @author howxu <dev@howxu.cn> */
public final class LightningNetworkResourceStorage extends NetworkResourceStorage<LightningTier> {
    public LightningNetworkResourceStorage(MEStorage storage, List<AEKey> keys) {
        super(storage, keys, LoadedLightningBridge.FAMILY.adapter());
    }
}
