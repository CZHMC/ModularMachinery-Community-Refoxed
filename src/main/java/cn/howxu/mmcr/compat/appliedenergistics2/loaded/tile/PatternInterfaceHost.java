package cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile;

import appeng.api.implementations.blockentities.ICraftingMachine;

/**
 * Host contract for MMCR pattern interfaces backed by AE2 pattern-provider logic.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface PatternInterfaceHost {
    ICraftingMachine craftingMachine();

    void onNativeReturnInventoryDrained();
}
