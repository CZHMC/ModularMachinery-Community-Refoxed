package cn.howxu.mmcr.publicapi.runtime;

import cn.howxu.mmcr.publicapi.presentation.IoDisplay;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jetbrains.annotations.ApiStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** MMCR-provided capability collection snapshot. Amount queries read live storage.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface IoSnapshot {
    IoSnapshot forTags(Set<String> requiredTags);
    List<IoDisplay> displays();
    List<ResourceAmount<ItemResource>> itemInputs();
    List<ResourceAmount<FluidResource>> fluidInputs();
    List<ResourceAmount<Identifier>> chemicalInputs();
    long chemicalAmount(Identifier chemicalId);
    long chemicalTagAmount(Identifier tagId);
    long chemicalOutputCapacity(Identifier chemicalId);
    List<HeatState> heatInputs();
    List<HeatState> heatOutputs();
    long energyInput();
    long itemAmount(Ingredient ingredient);
    long fluidAmount(FluidIngredient ingredient);
    long itemOutputCapacity(ItemStack stack);
    long fluidOutputCapacity(FluidStack stack);
    long energyOutputCapacity();
    Optional<Float> smartInterfaceValue(String name);
    Map<String, Float> smartInterfaceValues();
}
