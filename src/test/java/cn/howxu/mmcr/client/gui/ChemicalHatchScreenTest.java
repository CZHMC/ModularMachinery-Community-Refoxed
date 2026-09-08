package cn.howxu.mmcr.client.gui;

import cn.howxu.mmcr.client.render.ChemicalGuiRenderer;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the pure chemical display state used by the hatch screen.
 *
 * @author howxu <dev@howxu.cn>
 */
class ChemicalHatchScreenTest {
    @Test
    void chemical_fill_uses_the_chemical_tint_and_nonzero_fill_height() {
        ChemicalGuiRenderer.ChemicalRenderState state = ChemicalGuiRenderer.state(
                Identifier.parse("mekanism:oxygen"), 0xFF66CCFF, 500, 1_000, 61);

        assertThat(state.fillHeight()).isEqualTo(31);
        assertThat(state.tint()).isEqualTo(0xFF66CCFF);
        assertThat(state.identifier()).isEqualTo(Identifier.parse("mekanism:oxygen"));
    }

    @Test
    void chemical_display_is_empty_when_no_loaded_chemical_is_available() {
        ChemicalGuiRenderer.ChemicalRenderState state = ChemicalGuiRenderer.state(
                null, 0xFFFFFFFF, 500, 1_000, 61);

        assertThat(state.fillHeight()).isZero();
        assertThat(state.identifier()).isNull();
    }
}
