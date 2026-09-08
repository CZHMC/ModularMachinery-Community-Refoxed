package cn.howxu.mmcr.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the pure heat display text used by the hatch screen.
 *
 * @author howxu <dev@howxu.cn>
 */
class HeatHatchScreenTest {
    @Test
    void heat_screen_displays_temperature_and_capacity() {
        List<Component> lines = HeatHatchScreen.displayLines(12_000D, 300D);

        assertThat(lines).hasSize(3);
        assertThat(contents(lines.get(0)).getKey()).isEqualTo("gui.mmcr.heat.tooltip.amount");
        assertThat(contents(lines.get(0)).getArgs()[0]).isEqualTo("12,000");
        assertThat(contents(lines.get(1)).getKey()).isEqualTo("gui.mmcr.heat.tooltip.capacity");
        assertThat(contents(lines.get(1)).getArgs()[0]).isEqualTo("300");
        assertThat(contents(lines.get(2)).getKey()).isEqualTo("gui.mmcr.heat.tooltip.temperature");
        assertThat(contents(lines.get(2)).getArgs()[0]).isEqualTo("40.0");
    }

    @Test
    void heat_screen_uses_a_safe_zero_temperature_for_invalid_capacity() {
        List<Component> lines = HeatHatchScreen.displayLines(12_000D, 0D);

        assertThat(contents(lines.get(0)).getArgs()[0]).isEqualTo("12,000");
        assertThat(contents(lines.get(1)).getArgs()[0]).isEqualTo("0");
        assertThat(contents(lines.get(2)).getArgs()[0]).isEqualTo("0.0");
    }

    @Test
    void heat_screen_display_lines_do_not_embed_english_labels() {
        assertThat(HeatHatchScreen.displayLines(12_000D, 300D))
                .allMatch(line -> line.getContents() instanceof TranslatableContents);
    }

    private static TranslatableContents contents(Component component) {
        assertThat(component.getContents()).isInstanceOf(TranslatableContents.class);
        return (TranslatableContents) component.getContents();
    }
}
