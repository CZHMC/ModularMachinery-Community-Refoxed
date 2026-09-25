package cn.howxu.mmcr.api.machine;

import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SmartInterfaceModifierTest {

    @Test
    void maps_interface_values_to_numeric_machine_modifiers() {
        SmartInterfaceModifier mapping = SmartInterfaceModifier.numeric(
                "speed", "duration", "input", 0F, 10F, 1F, 0.5F, "multiply", false);

        assertThat(mapping.toModifier(5F)).isEqualTo(
                MachineModifier.numeric("duration", "input", 0.75D, "multiply", false));
    }

    @Test
    void rejects_boolean_parallelized_target() {
        assertThatIllegalArgumentException().isThrownBy(() -> SmartInterfaceModifier.numeric(
                "mode", "parallelized", "recipe", 0F, 1F, 0F, 1F, "multiply", false));
    }
}
