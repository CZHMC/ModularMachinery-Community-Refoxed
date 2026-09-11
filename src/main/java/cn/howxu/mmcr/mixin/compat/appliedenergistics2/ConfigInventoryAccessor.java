package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.util.ConfigInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Enables unbounded storage amounts for the stocking interface display mirror.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(ConfigInventory.class)
public interface ConfigInventoryAccessor {
    @Mutable
    @Accessor("allowOverstacking")
    void mmcr$setAllowOverstacking(boolean allowOverstacking);
}
