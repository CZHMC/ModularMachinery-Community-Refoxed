package cn.howxu.mmcr.compat.appliedenergistics2.util;

import appeng.api.inventories.InternalInventory;
import appeng.util.ConfigMenuInventory;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBaseBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBlockEntity;

/**
 * Identifies the MMCR output hosts and their cache-backed AE2 menu slots.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class InterfaceMenuPolicy {
    private InterfaceMenuPolicy() {
    }

    public static boolean isOutputHost(Object target) {
        return target instanceof OutputInterfaceBaseBlockEntity;
    }

    public static boolean isOutputStorageSlot(Object target, InternalInventory inventory) {
        if (!(target instanceof OutputInterfaceBaseBlockEntity output)
                || !(inventory instanceof ConfigMenuInventory wrapper)) {
            return false;
        }
        return wrapper.getDelegate() == output.getInterfaceLogic().getStorage();
    }

    public static boolean isExtractableOutputStorageSlot(Object target, InternalInventory inventory) {
        return target instanceof OutputInterfaceBlockEntity && isOutputStorageSlot(target, inventory);
    }
}
