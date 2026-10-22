package cn.howxu.mmcr.compat.jei;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.runtime.IJeiKeyMapping;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.InputWithModifiers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies JEI structure-preview input forwarding.
 *
 * @author howxu <dev@howxu.cn>
 */
class JeiStructurePreviewWidgetTest {

    @ParameterizedTest
    @CsvSource({"false, 69", "true, 87"})
    void last_control_consumes_simulation_and_toggles_only_on_real_click(boolean multipleStages, int highlightX) {
        RecordingPreview preview = new RecordingPreview();
        preview.multipleStages = multipleStages;
        JeiStructurePreviewWidget widget = JeiStructurePreviewWidget.forTesting(preview, 0, 0, 168, 100);

        assertThat(widget.handleInput(highlightX, 88, input(true))).isTrue();
        assertThat(preview.highlightsEnabled).isFalse();
        assertThat(preview.highlightToggles).isZero();
        assertThat(widget.handleMouseDragged(highlightX + 1, 89,
                InputConstants.Type.MOUSE.getOrCreate(0), 1, 1)).isFalse();
        assertThat(widget.handleInput(highlightX, 88, input(false))).isTrue();
        assertThat(preview.highlightsEnabled).isTrue();
        assertThat(preview.highlightToggles).isEqualTo(1);
        assertThat(preview.clicks).isZero();
        assertThat(preview.drags).isZero();
        assertThat(preview.releases).isZero();
        assertThat(preview.stageChanges).isZero();

        assertThat(widget.handleInput(highlightX + 13, 100, input(true))).isTrue();
        assertThat(preview.highlightsEnabled).isTrue();
        assertThat(widget.handleInput(highlightX + 13, 100, input(false))).isTrue();
        assertThat(preview.highlightsEnabled).isFalse();
        assertThat(preview.highlightToggles).isEqualTo(2);
        assertThat(preview.clicks).isZero();

        // The gap after H must not become another control or begin a preview drag.
        assertThat(widget.handleInput(highlightX + 14, 100, input(false))).isFalse();
        assertThat(widget.handleInput(highlightX + 18, 100, input(false))).isFalse();
        assertThat(preview.highlightToggles).isEqualTo(2);
    }

    @Test
    void multi_stage_control_still_changes_stage_and_reset_keeps_its_mapping() {
        RecordingPreview preview = new RecordingPreview();
        preview.multipleStages = true;
        JeiStructurePreviewWidget widget = JeiStructurePreviewWidget.forTesting(preview, 0, 0, 168, 100);

        assertThat(widget.handleInput(70, 88, input(true))).isTrue();
        assertThat(preview.stageChanges).isZero();
        assertThat(widget.handleInput(70, 88, input(false))).isTrue();
        assertThat(preview.stageChanges).isEqualTo(1);
        assertThat(widget.handleInput(52, 88, input(false))).isTrue();
        assertThat(preview.resets).isEqualTo(1);
        assertThat(preview.highlightToggles).isZero();
        assertThat(preview.clicks).isZero();
    }

    @Test
    void starts_dragging_during_the_mouse_down_simulation_pass() {
        RecordingPreview preview = new RecordingPreview();
        JeiStructurePreviewWidget widget = JeiStructurePreviewWidget.forTesting(preview, 0, 0, 20, 20);
        InputConstants.Key leftMouse = InputConstants.Type.MOUSE.getOrCreate(0);

        assertThat(widget.handleInput(12, 2, input(true))).isTrue();
        assertThat(widget.handleMouseDragged(16, 6, leftMouse, 4, 4)).isTrue();
        assertThat(widget.handleInput(16, 6, input(false))).isTrue();

        assertThat(preview.clicks).isEqualTo(1);
        assertThat(preview.drags).isEqualTo(1);
        assertThat(preview.releases).isEqualTo(1);
    }

    private static IJeiUserInput input(boolean simulate) {
        return new IJeiUserInput() {
            @Override
            public InputConstants.Key getKey() {
                return InputConstants.Type.MOUSE.getOrCreate(0);
            }

            @Override
            public int getModifiers() {
                return 0;
            }

            @Override
            public InputWithModifiers getInputWithModifiers() {
                return null;
            }

            @Override
            public boolean isSimulate() {
                return simulate;
            }

            @Override
            public boolean is(KeyMapping keyMapping) {
                return false;
            }

            @Override
            public boolean is(IJeiKeyMapping keyMapping) {
                return false;
            }
        };
    }

    private static final class RecordingPreview implements JeiStructurePreviewWidget.Preview {
        private int clicks;
        private int drags;
        private int releases;
        private boolean multipleStages;
        private boolean highlightsEnabled;
        private int highlightToggles;
        private int stageChanges;
        private int resets;

        @Override
        public boolean hasMultipleStages() {
            return multipleStages;
        }

        @Override
        public boolean positionHighlightsEnabled() {
            return highlightsEnabled;
        }

        @Override
        public void togglePositionHighlights() {
            highlightsEnabled = !highlightsEnabled;
            highlightToggles++;
        }

        @Override
        public void selectNextStage() {
            stageChanges++;
        }

        @Override
        public void reset() {
            resets++;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            clicks++;
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            releases++;
            return true;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            drags++;
            return true;
        }
    }
}
