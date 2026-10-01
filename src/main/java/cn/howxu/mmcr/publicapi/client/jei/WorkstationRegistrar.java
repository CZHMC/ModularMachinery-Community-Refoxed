package cn.howxu.mmcr.publicapi.client.jei;

import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.ApiStatus;

/** MMCR-provided workstation registration window; no JEI dependency is needed to contribute.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface WorkstationRegistrar {
    void addRecipePoolWorkstation(Identifier poolId, Identifier itemId);
    void addRecipePoolWorkstation(Identifier poolId, ItemLike workstation);
    void addRecipePoolWorkstation(Identifier poolId, ItemStack workstation);
    void addMachineWorkstation(Identifier machineId, Identifier recipeTypeId);
    List<Workstation> entries();
}
