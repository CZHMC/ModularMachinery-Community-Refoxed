package cn.howxu.mmcr.internal.async;

import cn.howxu.mmcr.internal.runtime.MachineWorkMode;

/**
 * Immutable context for one machine continuation execution.
 *
 * @author howxu <dev@howxu.cn>
 */
public record AsyncExecutionContext(MachineAsyncCoordinator.TaskKey key) {
    public MachineWorkMode workMode() {
        return key.workMode();
    }
}
