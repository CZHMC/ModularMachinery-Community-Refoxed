package cn.howxu.mmcr.test;

import cn.howxu.mmcr.config.ServerConfig;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;

/** Provides configuration setup that simulates entering a world in tests.
 * @author howxu <dev@howxu.cn>
 */
public final class ConfigTestSupport {
    private ConfigTestSupport() {
    }

    public static void setMachineWorkMode(MachineWorkMode mode) {
        ServerConfig.MACHINE_WORK_MODE.clearCache();
        ServerConfig.MACHINE_WORK_MODE.set(mode);
    }
}
