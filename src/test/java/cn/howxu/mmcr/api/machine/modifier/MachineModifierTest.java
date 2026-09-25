package cn.howxu.mmcr.api.machine.modifier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MachineModifierTest {

    @Test
    void numeric_modifier_rejects_invalid_target_scope_and_chance_combinations() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> MachineModifier.numeric("energy", "output", 1D, "multiply", false));
        assertThatIllegalArgumentException().isThrownBy(
                () -> MachineModifier.numeric("parallelism", "machine", 1D, "add", true));
        assertThatIllegalArgumentException().isThrownBy(
                () -> MachineModifier.numeric("duration", "input", Double.NaN, "multiply", false));
    }

    @Test
    void parallelized_modifier_is_distinct_from_numeric_modifiers() {
        assertThat(MachineModifier.parallelized(false).value()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(
                () -> MachineModifier.numeric("parallelized", "recipe", 1D, "add", false));
    }
}
