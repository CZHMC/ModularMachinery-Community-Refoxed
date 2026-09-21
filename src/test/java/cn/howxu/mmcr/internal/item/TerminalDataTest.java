package cn.howxu.mmcr.internal.item;

import cn.howxu.mmcr.test.TestBootstrap;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author howxu <dev@howxu.cn>
 */
class TerminalDataTest {

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void defaults_are_inventory_lowest_stage_all_layers_and_preview_off() {
        assertThat(TerminalData.DEFAULT.inventoryMode()).isEqualTo(TerminalInventoryMode.INVENTORY);
        assertThat(TerminalData.DEFAULT.container()).isNull();
        assertThat(TerminalData.DEFAULT.stage()).isEqualTo(1);
        assertThat(TerminalData.DEFAULT.previewEnabled()).isFalse();
        assertThat(TerminalData.DEFAULT.previewLayer()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void level_selection_is_remembered_per_type() {
        Identifier coils = Identifier.parse("test:coils");
        Identifier voltage = Identifier.parse("test:voltage");
        TerminalData data = TerminalData.DEFAULT
                .withSelectedLevel(coils, Identifier.parse("test:iron"))
                .withSelectedLevel(voltage, Identifier.parse("test:high"));

        assertThat(data.selectedLevels()).containsEntry(coils, Identifier.parse("test:iron"))
                .containsEntry(voltage, Identifier.parse("test:high"));
    }

    @Test
    void clear_returns_the_exact_default_snapshot() {
        TerminalData data = TerminalData.DEFAULT.withStage(4).withPreview(true, 12)
                .withInventoryMode(TerminalInventoryMode.CONTAINER);

        assertThat(data.clear()).isEqualTo(TerminalData.DEFAULT);
    }

    @Test
    void ae2_access_point_binding_is_retained_in_ae2_mode() {
        GlobalPos accessPoint = GlobalPos.of(Level.OVERWORLD, new BlockPos(3, 64, 5));

        TerminalData data = TerminalData.DEFAULT.withAe2AccessPoint(accessPoint)
                .withInventoryMode(TerminalInventoryMode.AE2);

        assertThat(data.ae2AccessPoint()).isEqualTo(accessPoint);
        assertThat(data.inventoryMode()).isEqualTo(TerminalInventoryMode.AE2);
    }

    @Test
    void legacy_data_without_an_ae2_access_point_still_decodes() {
        var legacyData = TerminalData.CODEC.encodeStart(JsonOps.INSTANCE, TerminalData.DEFAULT)
                .getOrThrow().getAsJsonObject();
        legacyData.remove("ae2_access_point");

        TerminalData decoded = TerminalData.CODEC.parse(JsonOps.INSTANCE, legacyData).getOrThrow();

        assertThat(decoded).isEqualTo(TerminalData.DEFAULT);
    }

    @Test
    void switching_to_inventory_clears_external_storage_bindings() {
        TerminalData data = TerminalData.DEFAULT
                .withContainer(GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 1)))
                .withAe2AccessPoint(GlobalPos.of(Level.OVERWORLD, new BlockPos(2, 64, 2)));

        TerminalData switched = data.withInventoryMode(TerminalInventoryMode.INVENTORY);

        assertThat(switched.container()).isNull();
        assertThat(switched.ae2AccessPoint()).isNull();
    }
}
