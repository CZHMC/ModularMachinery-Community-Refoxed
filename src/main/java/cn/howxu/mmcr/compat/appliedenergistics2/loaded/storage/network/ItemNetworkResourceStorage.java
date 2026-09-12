package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.network;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.ItemResourceStorage;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;

/**
 * Item-resource view over an AE2 network.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class ItemNetworkResourceStorage extends NetworkResourceStorage<ItemResource> {
    public ItemNetworkResourceStorage(MEStorage meStorage, List<AEKey> keys) {
        super(meStorage, keys, ItemResourceStorage.adapter());
    }
}
