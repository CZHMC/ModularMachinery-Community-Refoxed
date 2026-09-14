package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.helpers.externalstorage.GenericStackInv;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2KeyAdapter;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Item-resource view over an AE2 generic stack inventory.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class ItemResourceStorage extends ResourceStorage<ItemResource> {
    public ItemResourceStorage(GenericStackInv inventory) {
        super(inventory, adapter());
    }

    public static AE2KeyAdapter<ItemResource> adapter() {
        return AE2ResourceFamilies.ITEM.adapter();
    }
}
