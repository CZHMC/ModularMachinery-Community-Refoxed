package cn.howxu.mmcr.client.preview;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies shared structure-preview panel state helpers.
 *
 * @author howxu <dev@howxu.cn>
 */
class StructurePreviewPanelTest {
    @Test
    void candidateWindowRotatesEverySecond() {
        assertThat(StructurePreviewPanel.candidateIndex(0, 0L, 4)).isZero();
        assertThat(StructurePreviewPanel.candidateIndex(0, 999L, 4)).isZero();
        assertThat(StructurePreviewPanel.candidateIndex(0, 1_000L, 4)).isOne();
        assertThat(StructurePreviewPanel.candidateIndex(7, 0L, 4)).isEqualTo(3);
    }

    @Test
    void materialPagesContainEightEntriesAndRotateEveryEightSeconds() {
        assertThat(StructurePreviewPanel.materialPage(0L, 16)).isZero();
        assertThat(StructurePreviewPanel.materialPage(7_999L, 16)).isZero();
        assertThat(StructurePreviewPanel.materialPage(8_000L, 16)).isOne();
    }

    @Test
    void singleStageDoesNotAdvance() {
        assertThat(StructurePreviewPanel.stageIndexAfter(0, 1)).isZero();
    }
}
