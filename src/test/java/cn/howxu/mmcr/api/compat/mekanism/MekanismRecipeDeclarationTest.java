package cn.howxu.mmcr.api.compat.mekanism;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the Mekanism-neutral recipe declaration values.
 *
 * @author howxu <dev@howxu.cn>
 */
class MekanismRecipeDeclarationTest {
    @Test
    void chemical_declarations_distinguish_exact_id_and_tag() {
        assertEquals(ChemicalIngredient.Kind.CHEMICAL,
                ChemicalIngredient.chemical(Identifier.parse("mekanism:oxygen"), 1_000).kind());
        assertEquals(ChemicalIngredient.Kind.TAG,
                ChemicalIngredient.tag(Identifier.parse("mekanism:fuels"), 1_000).kind());
        assertThrows(IllegalArgumentException.class,
                () -> ChemicalIngredient.chemical(Identifier.parse("mekanism:oxygen"), 0));
    }

    @Test
    void chemical_output_and_heat_declarations_validate_values() {
        ChemicalOutput output = ChemicalOutput.of(Identifier.parse("mekanism:oxygen"), 1_000, 0.5F);
        assertEquals(Identifier.parse("mekanism:oxygen"), output.id());
        assertEquals(1_000, output.amount());
        assertEquals(0.5F, output.chance());
        assertEquals(HeatRequirement.Kind.MINIMUM_TEMPERATURE, HeatRequirement.minimumTemperature(300D).kind());
        assertEquals(300D, HeatRequirement.minimumTemperature(300D).value());
        assertEquals(HeatRequirement.Kind.OUTPUT_HEAT, HeatRequirement.outputHeat(4D).kind());
        assertEquals(4D, HeatRequirement.outputHeat(4D).value());
        assertThrows(IllegalArgumentException.class,
                () -> ChemicalOutput.of(Identifier.parse("mekanism:oxygen"), 1_000, 1.1F));
        assertThrows(IllegalArgumentException.class, () -> HeatRequirement.minimumTemperature(-1D));
    }

    @Test
    void port_family_ids_are_stable() {
        assertEquals(Identifier.parse("mmcr:mekanism_chemical"), MekanismPortFamilies.CHEMICAL);
        assertEquals(Identifier.parse("mmcr:mekanism_heat_temperature"), MekanismPortFamilies.HEAT_TEMPERATURE);
        assertEquals(Identifier.parse("mmcr:mekanism_heat"), MekanismPortFamilies.HEAT);
    }
}
