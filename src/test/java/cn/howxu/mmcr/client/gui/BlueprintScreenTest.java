package cn.howxu.mmcr.client.gui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies blueprint screen layout and coordinate mapping.
 * @author howxu <dev@howxu.cn>
 */
class BlueprintScreenTest {
    @Test
    void layoutKeepsThePreviewOnTheLeftOfTheInformationColumn() {
        BlueprintLayout layout = BlueprintScreen.layoutFor(640, 360, false);

        assertThat(layout.preview().x()).isLessThan(layout.title().x());
        assertThat(layout.preview().width()).isGreaterThan(layout.title().width());
        assertThat(layout.materials().y()).isGreaterThan(layout.preview().y());
        assertThat(layout.nextStageButton()).isNull();
    }

    @Test
    void layoutUsesSquareMaterialSlotsAndKeepsCandidatesAbovePreviewBottom() {
        BlueprintLayout layout = BlueprintScreen.layoutFor(640, 480, true);

        assertThat(layout.materialSlotSize()).isEqualTo(25);
        assertThat(layout.materialColumns()).isEqualTo(10);
        assertThat(layout.materialSlotGap()).isEqualTo(2);
        assertThat(layout.candidates().y() + layout.candidates().height())
                .isLessThanOrEqualTo(layout.preview().y() + layout.preview().height());
        assertThat(layout.controls().y() + layout.controls().height())
                .isEqualTo(layout.top() + Math.round(BlueprintScreen.baseHeight() * layout.scale()) - 8);
    }

    @Test
    void scrollbarDragMapsToClampedRowOffsets() {
        assertThat(BlueprintScreen.clampScrollOffset(8, 6, 3)).isEqualTo(3);
        assertThat(BlueprintScreen.scrollbarHandleY(3, 6, 3, 10, 60, 12)).isEqualTo(58);
        assertThat(BlueprintScreen.scrollOffsetFromScrollbarY(58, 6, 3, 10, 60, 12, 0)).isEqualTo(3);
    }

    @Test
    void nextStageButtonOnlyExistsForMultipleStagesAndSpansTwoColumns() {
        BlueprintLayout layout = BlueprintScreen.layoutFor(640, 360, true);

        assertThat(layout.nextStageButton()).isNotNull();
        assertThat(layout.nextStageButton().width())
                .isEqualTo(layout.layerButtons()[0].width() * 2 + 6);
    }

    @Test
    void localMouseInvertsTheLayoutScale() {
        assertThat(BlueprintScreen.localMouse(130.0, 100, 0.5F)).isEqualTo(60.0);
    }

    @Test
    void previewMouseUsesTheActualScaledViewportCoordinates() {
        BlueprintLayout layout = BlueprintScreen.layoutFor(220, 140, false);

        assertThat(BlueprintScreen.previewMouse(
                layout.preview().x() + layout.preview().width() - 1.0,
                layout.preview().x()))
                .isEqualTo(layout.preview().width() - 1.0);
        assertThat(BlueprintScreen.previewMouse(
                layout.preview().y() + layout.preview().height() - 1.0,
                layout.preview().y()))
                .isEqualTo(layout.preview().height() - 1.0);
    }
}
