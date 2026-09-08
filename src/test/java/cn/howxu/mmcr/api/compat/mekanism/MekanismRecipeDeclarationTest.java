package cn.howxu.mmcr.api.compat.mekanism;

import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.FailureReason;
import cn.howxu.mmcr.api.capability.status.FailureReasonRegistry;
import cn.howxu.mmcr.api.capability.status.StatusSeverity;
import cn.howxu.mmcr.compat.mekanism.MekanismBridgeBootstrap;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

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

    @Test
    void production_initialization_registers_all_failure_reasons() {
        MekanismBridgeBootstrap.bootstrap();

        assertRegistered(MekanismFailureReasons.MEKANISM_UNAVAILABLE,
                "gui.mmcr.failure.mekanism_unavailable");
        assertRegistered(MekanismFailureReasons.CHEMICAL_INPUT_MISSING,
                "gui.mmcr.failure.chemical_input_missing");
        assertRegistered(MekanismFailureReasons.CHEMICAL_OUTPUT_BLOCKED,
                "gui.mmcr.failure.chemical_output_blocked");
        assertRegistered(MekanismFailureReasons.CHEMICAL_TYPE_MISMATCH,
                "gui.mmcr.failure.chemical_type_mismatch");
        assertRegistered(MekanismFailureReasons.CHEMICAL_RADIOACTIVITY_REJECTED,
                "gui.mmcr.failure.chemical_radioactivity_rejected");
        assertRegistered(MekanismFailureReasons.HEAT_TEMPERATURE_INSUFFICIENT,
                "gui.mmcr.failure.heat_temperature_insufficient");
        assertRegistered(MekanismFailureReasons.HEAT_OUTPUT_BLOCKED,
                "gui.mmcr.failure.heat_output_blocked");

        FailureReason reason = MekanismFailureReasons.CHEMICAL_INPUT_MISSING;
        ExecutionStatus status = new ExecutionStatus(reason.id(), StatusSeverity.BLOCKED, reason.id(),
                Map.of("reason", reason.id().toString()));
        assertEquals(reason, status.reason());
        assertThrows(IllegalArgumentException.class, MekanismFailureReasons::register);
    }

    private static void assertRegistered(FailureReason reason, String translationKey) {
        assertEquals(translationKey, reason.translationKey());
        assertEquals(reason, FailureReasonRegistry.find(reason.id()));
    }
}
