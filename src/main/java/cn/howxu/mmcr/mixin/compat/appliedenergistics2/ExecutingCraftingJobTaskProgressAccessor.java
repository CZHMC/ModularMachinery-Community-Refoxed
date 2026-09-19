package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accesses AE2's package-private task progress counter.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(targets = "appeng.crafting.execution.ExecutingCraftingJob$TaskProgress")
public interface ExecutingCraftingJobTaskProgressAccessor {
    @Accessor("value")
    long mmcr$value();

    @Accessor("value")
    void mmcr$setValue(long value);
}
