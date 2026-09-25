package cn.howxu.mmcr.client.model;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.MachineAppearanceSpec;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicOverlayBakedModelTest {
    @Test
    void incomplete_appearance_faces_use_uniform_fallback() {
        var faces = DynamicOverlayBakedModel.completeOrFallback(Map.of(Direction.NORTH, MMCR.id("block/north")));

        assertThat(faces).isEqualTo(DynamicOverlayBakedModel.FaceTextures.uniform(MMCR.id("block/basic_casing")));
    }

    @Test
    void complete_appearance_faces_are_preserved() {
        var faces = DynamicOverlayBakedModel.completeOrFallback(Map.of(
                Direction.DOWN, MMCR.id("block/down"), Direction.UP, MMCR.id("block/up"),
                Direction.NORTH, MMCR.id("block/north"), Direction.SOUTH, MMCR.id("block/south"),
                Direction.WEST, MMCR.id("block/west"), Direction.EAST, MMCR.id("block/east")));

        assertThat(faces.forFace(Direction.SOUTH)).isEqualTo(MMCR.id("block/south"));
    }

    @Test
    void controller_state_overlay_uses_the_machine_active_texture() {
        var machineId = MMCR.id("stateful_controller");
        MachineAppearanceCache.replaceSnapshot(Map.of(machineId, new MachineAppearanceSpec(
                MMCR.id("basic_casing"), null, null,
                MMCR.id("block/custom_idle"), MMCR.id("block/custom_active"))));

        assertThat(DynamicOverlayBakedModel.controllerStateOverlay(machineId, true))
                .isEqualTo(MMCR.id("block/custom_active"));
    }

    @Test
    void controller_state_overlay_uses_the_machine_idle_texture() {
        var machineId = MMCR.id("stateful_controller");
        MachineAppearanceCache.replaceSnapshot(Map.of(machineId, new MachineAppearanceSpec(
                MMCR.id("basic_casing"), null, null,
                MMCR.id("block/custom_idle"), MMCR.id("block/custom_active"))));

        assertThat(DynamicOverlayBakedModel.controllerStateOverlay(machineId, false))
                .isEqualTo(MMCR.id("block/custom_idle"));
    }
}
