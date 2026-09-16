package cn.howxu.mmcr.config;

import net.neoforged.neoforge.common.ModConfigSpec.RestartType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies MMCR configuration ownership and reload semantics.
 * @author howxu <dev@howxu.cn>
 */
class ConfigLayoutTest {
    @Test
    void assigns_values_to_their_client_common_and_server_sections() {
        assertThat(ClientConfig.PREVIEW_RENDER_RADIUS.getPath()).isEqualTo(List.of("preview", "render_radius"));
        assertThat(ServerConfig.MACHINE_WORK_MODE.getPath()).isEqualTo(List.of("machine", "work_mode"));
        assertThat(ServerConfig.BUILD_BLOCKS_PER_TICK.getPath()).isEqualTo(List.of("build", "blocks_per_tick"));
        assertThat(ServerConfig.MAX_REQUESTS_PER_TICK.getPath()).isEqualTo(List.of("network", "max_requests_per_tick"));
    }

    @Test
    void machine_work_mode_requires_a_world_restart() {
        assertThat(ServerConfig.MACHINE_WORK_MODE.getSpec().restartType()).isEqualTo(RestartType.WORLD);
    }
}
