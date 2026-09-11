package cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.helpers.externalstorage.GenericStackInv;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.Optional;

/**
 * Item-resource view over an AE2 generic stack inventory.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2ItemResourceStorage extends AE2ResourceStorage<ItemResource> {
    private static final AE2KeyAdapter<ItemResource> ADAPTER = new AE2KeyAdapter<>() {
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
    };

    public AE2ItemResourceStorage(GenericStackInv inventory) {
        super(inventory, adapter());
    }

    public static AE2KeyAdapter<ItemResource> adapter() {
        return ADAPTER;
    }
}
