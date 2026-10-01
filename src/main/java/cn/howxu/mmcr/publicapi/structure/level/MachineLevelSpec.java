package cn.howxu.mmcr.publicapi.structure.level;

import cn.howxu.mmcr.publicapi.recipe.modifier.ModifierBundle;
import cn.howxu.mmcr.publicapi.structure.BlockCondition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Library-owned declared or registered level view; representative stacks are defensive copies.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface MachineLevelSpec {
    Identifier id();
    Identifier typeId();
    int priority();
    BlockCondition statePredicate();
    ItemStack representative();
    ModifierBundle modifier();
}
