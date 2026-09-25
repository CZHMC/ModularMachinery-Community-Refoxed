package cn.howxu.mmcr.client.model;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.MachineAppearanceSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicOverlayItemModelTest {
    @AfterEach
    void clearAppearanceCache() {
        MachineAppearanceCache.replaceSnapshot(Map.of());
    }

    @Test
    void controller_items_display_the_idle_state_overlay() {
        var machineId = MMCR.id("stateful_controller");
        MachineAppearanceCache.replaceSnapshot(Map.of(machineId, new MachineAppearanceSpec(
                MMCR.id("basic_casing"), null, null,
                MMCR.id("block/custom_idle"), MMCR.id("block/custom_active"))));

        var description = DynamicOverlayItemModel.Description.controller(machineId);

        assertThat(description.stateOverlayTexture()).isEqualTo(MMCR.id("block/custom_idle"));
    }
}
