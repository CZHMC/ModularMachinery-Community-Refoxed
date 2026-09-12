package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.inventories.InternalInventory;
import appeng.util.ConfigMenuInventory;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2OutputInterfaceBaseBlockEntity;

/**
 * Identifies the MMCR output hosts and their cache-backed AE2 menu slots.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2InterfaceMenuPolicy {
    private AE2InterfaceMenuPolicy() {
    }

    public static boolean isOutputHost(Object target) {
        return target instanceof AE2OutputInterfaceBaseBlockEntity;
    }

    public static boolean isOutputStorageSlot(Object target, InternalInventory inventory) {
        if (!(target instanceof AE2OutputInterfaceBaseBlockEntity output)
                || !(inventory instanceof ConfigMenuInventory wrapper)) {
            return false;
        }
        return wrapper.getDelegate() == output.getInterfaceLogic().getStorage();
    }
}
