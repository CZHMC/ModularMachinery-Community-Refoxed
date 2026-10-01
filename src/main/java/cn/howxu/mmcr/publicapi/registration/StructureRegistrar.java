package cn.howxu.mmcr.publicapi.registration;

import cn.howxu.mmcr.publicapi.structure.StructureDraft;
import cn.howxu.mmcr.publicapi.structure.StructureSpec;
import cn.howxu.mmcr.publicapi.structure.level.LevelTypeSpec;
import cn.howxu.mmcr.publicapi.structure.level.MachineLevelSpec;
import cn.howxu.mmcr.publicapi.recipe.modifier.ModifierBundle;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** MMCR-provided shared Java/KJS structure registration window.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface StructureRegistrar {
    void registerStructure(Identifier machineId, Consumer<StructureDraft> configuration);
    void registerStructure(StructureSpec structure);
    void registerLevelType(LevelTypeSpec type);
    void registerLevel(MachineLevelSpec level);
    void registerModifier(Identifier id, ModifierBundle modifier);
    void registerModifierItem(ItemStack stack, Identifier modifierId);
    Map<Identifier, StructureSpec> structures();
    Map<Identifier, LevelTypeSpec> levelTypes();
    Map<Identifier, MachineLevelSpec> levels();
    Map<Identifier, ModifierBundle> modifiers();
    Map<Identifier, List<ItemStack>> modifierItems();
}
