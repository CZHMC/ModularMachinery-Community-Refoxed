package cn.howxu.mmcr.compat.jei;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.compat.mekanism.ChemicalIngredient;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.FluidRequirement;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandlerRegistry;
import cn.howxu.mmcr.compat.mekanism.MekanismRecipeTypes;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedChemicalRequirement;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JEI display surfaces fluid and chemical input consumeChance to its overlay/tooltip code path.
 *
 * @author howxu <dev@howxu.cn>
 */
class FluidChemicalConsumeChanceDisplayTest {
    private static final Identifier MACHINE = MMCR.id("display_consume_chance");

    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrap();
        RequirementHandlerRegistry.register(LoadedChemicalRequirement.TYPE);
        LoadedChemicalRequirement.installUnavailableHandler();
    }

    @Test
    void fluid_input_display_carries_consume_chance() {
        MachineRequirement fluidInput = new FluidRequirement(RecipeModifier.IOType.INPUT,
                FluidIngredient.of(Fluids.WATER), 1000, FluidStack.EMPTY, 1F, List.of(), 0.25F);
        MachineRecipe recipe = MachineRecipe.fromCanonical(MMCR.id("fluid_consume"), MACHINE, 20,
                List.of(fluidInput), List.of(), List.of(), 0, 1, false, false, List.of(), false, Set.of());

        MachineRecipeDisplay display = MachineRecipeDisplay.from(recipe);

        MachineRecipeDisplay.FluidInputDisplay fluidDisplay = display.fluidInputs().get(0);
        assertThat(fluidDisplay.amount()).isEqualTo(1000);
        assertThat(fluidDisplay.consumeChance()).isEqualTo(0.25F);
    }

    @Test
    void chemical_input_display_carries_consume_chance() {
        MachineRequirement chemicalInput = new LoadedChemicalRequirement(RecipeModifier.IOType.INPUT,
                ChemicalIngredient.chemical(Identifier.parse("mekanism:oxygen"), 1_000L), 1F, List.of(), 0F);
        MachineRecipe recipe = MachineRecipe.fromCanonical(MMCR.id("chemical_consume"), MACHINE, 20,
                List.of(chemicalInput), List.of(), List.of(), 0, 1, false, false, List.of(), false, Set.of());

        MachineRecipeDisplay display = MachineRecipeDisplay.from(recipe);

        JeiDisplayEntry chemical = display.entries().stream()
                .filter(entry -> entry.typeId().equals(MekanismRecipeTypes.CHEMICAL))
                .findFirst().orElseThrow();
        assertThat(chemical.chance()).isEqualTo(0F);
    }
}
