package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.api.stacks.AEKeyType;
import appeng.crafting.execution.ElapsedTimeTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invokes AE2's package-private waiting-container accounting method.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(ElapsedTimeTracker.class)
public interface ElapsedTimeTrackerAccessor {
    @Invoker("addMaxItems")
    void mmcr$addMaxItems(long amount, AEKeyType type);
}
