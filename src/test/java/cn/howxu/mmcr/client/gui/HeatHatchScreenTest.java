package cn.howxu.mmcr.client.gui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the pure heat display text used by the hatch screen.
 *
 * @author howxu <dev@howxu.cn>
 */
class HeatHatchScreenTest {
    @Test
    void heat_screen_displays_temperature_and_capacity() {
        assertThat(HeatHatchScreen.displayLines(12_000D, 300D))
                .contains("12,000", "300", "40.0 K");
    }

    @Test
    void heat_screen_uses_a_safe_zero_temperature_for_invalid_capacity() {
        assertThat(HeatHatchScreen.displayLines(12_000D, 0D))
                .contains("12,000", "0", "0.0 K");
    }
}
