package cn.howxu.mmcr.internal.async.contract;

import cn.howxu.mmcr.internal.async.AsyncContinuation;
import cn.howxu.mmcr.internal.async.MachineAsyncCoordinator;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncExecutionContextContractTest {

    @Test
    void public_continuations_can_read_the_work_mode_from_their_context() {
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.forTesting(Runnable::run);
        var key = new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 40L, MachineWorkMode.SEMI_SYNC);

        assertThat(coordinator.submit(key, context -> {
            assertThat(context.workMode()).isEqualTo(MachineWorkMode.SEMI_SYNC);
            return AsyncContinuation.Yield.complete();
        })).isTrue();
    }
}
