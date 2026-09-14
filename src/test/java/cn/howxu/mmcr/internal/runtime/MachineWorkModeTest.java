package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.config.Config;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.fml.config.IConfigSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MachineWorkModeTest {
    @BeforeAll
    static void loadConfig() throws ReflectiveOperationException {
        CommentedConfig config = CommentedConfig.inMemory();
        Config.SPEC.correct(config);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Config.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null));
    }

    @Test
    void async_is_the_default_global_machine_work_mode() {
        assertThat(Config.MACHINE_WORK_MODE.get()).isEqualTo(MachineWorkMode.ASYNC);
        assertThat(MachineWorkMode.values()).containsExactly(
                MachineWorkMode.ASYNC, MachineWorkMode.SEMI_SYNC, MachineWorkMode.SYNC);
    }
}
