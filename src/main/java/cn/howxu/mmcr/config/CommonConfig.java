package cn.howxu.mmcr.config;

import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Defines MMCR configuration values shared by both physical sides.
 * @author howxu <dev@howxu.cn>
 */
public final class CommonConfig {
    public static final ModConfigSpec.EnumValue<MachineWorkMode> MACHINE_WORK_MODE;
    public static final ModConfigSpec SPEC;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("machine");
        MACHINE_WORK_MODE = builder
                .comment("Global machine execution mode")
                .worldRestart()
                .defineEnum("work_mode", MachineWorkMode.ASYNC);
        builder.pop();
        SPEC = builder.build();
    }

    private CommonConfig() {
    }
}
