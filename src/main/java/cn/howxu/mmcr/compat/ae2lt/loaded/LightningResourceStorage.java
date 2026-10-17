package cn.howxu.mmcr.compat.ae2lt.loaded;

import appeng.helpers.externalstorage.GenericStackInv;
import cn.howxu.mmcr.api.compat.ae2lt.LightningTier;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.ResourceStorage;

/** @author howxu <dev@howxu.cn> */
public final class LightningResourceStorage extends ResourceStorage<LightningTier> {
    public LightningResourceStorage(GenericStackInv inventory) {
        super(inventory, LoadedLightningBridge.FAMILY.adapter());
    }
}
