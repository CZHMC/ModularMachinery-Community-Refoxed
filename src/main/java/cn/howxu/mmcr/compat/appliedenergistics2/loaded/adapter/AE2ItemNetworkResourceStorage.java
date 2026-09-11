package cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;

/**
 * Item-resource view over an AE2 network.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2ItemNetworkResourceStorage extends AE2NetworkResourceStorage<ItemResource> {
    public AE2ItemNetworkResourceStorage(MEStorage meStorage, List<AEKey> keys) {
        super(meStorage, keys, AE2ItemResourceStorage.adapter());
    }
}
