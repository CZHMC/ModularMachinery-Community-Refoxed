package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.config.Config;
import cn.howxu.mmcr.internal.async.AsyncContinuation;
import cn.howxu.mmcr.internal.async.AsyncExecutionContext;
import cn.howxu.mmcr.internal.async.MachineAsyncCoordinator;
import cn.howxu.mmcr.internal.async.MainThreadStep;
import cn.howxu.mmcr.internal.multiblock.SharedIoCoordinator;
import cn.howxu.mmcr.internal.multiblock.StructureClaimRegistry;
import cn.howxu.mmcr.internal.runtime.AsyncCraftingExecution;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.test.RecipeTestSupport;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.TestBootstrap;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.config.IConfigSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies shared-IO asynchronous recipe execution boundaries.
 *
 * @author howxu <dev@howxu.cn>
 */
class AsyncCraftingExecutionTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
        CommentedConfig config = CommentedConfig.inMemory();
        Config.SERVER_SPEC.correct(config);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Config.SERVER_SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null));
    }

    @Test
    void async_finish_release_commits_its_continuation_in_the_same_shared_io_fence() {
        Config.MACHINE_WORK_MODE.set(MachineWorkMode.ASYNC);
        Identifier machineId = MMCR.id("test_cube");
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controllerEntity(machineId, BlockPos.ZERO);
        RuntimeTestFixtures.formStructure(controller,
                new DynamicMachine(machineId, "async execution", new BlockArray(Map.of())));
        controller.setFormed(true);
        RuntimeTestFixtures.republish(controller);
        ServerLevel level = (ServerLevel) controller.getLevel();
        StructureClaimRegistry registry = StructureClaimRegistry.get(level);
        assertThat(registry.claim(controller.getBlockPos(), List.of()).accepted()).isTrue();

        MachineRecipe recipe = RecipeTestSupport.create(MMCR.id("async_execution_finish"), machineId, 1,
                List.of(), List.of());
        FactoryRecipeThread thread = FactoryRecipeThread.simple(controller);
        AtomicInteger finishes = new AtomicInteger();
        thread.setFinishContinuation(finishes::incrementAndGet);

        assertThat(thread.searchAndStartRecipe(List.of(recipe), 1,
                controller.runtimeSnapshot().structure().version())).isTrue();
        completeTick(controller);
        long epochBeforeFinish = controller.resourceAvailabilityEpoch();
        MachineAsyncCoordinator.TaskKey tickKey = new MachineAsyncCoordinator.TaskKey(controller.getBlockPos(),
                level.getGameTime(), MachineWorkMode.ASYNC, thread.asyncLaneId());
        thread.tick();
        completeTick(controller);
        assertThat(MachineAsyncCoordinator.get(level).failureFor(tickKey)).isNull();
        thread.tick();
        completeTick(controller);

        assertThat(thread.runtime().active()).isFalse();
        assertThat(finishes).hasValue(1);
        assertThat(controller.resourceAvailabilityEpoch()).isEqualTo(epochBeforeFinish + 1L);
    }

    @Test
    void finished_async_lane_restarts_on_the_next_tick_through_the_shared_io_fence() {
        Config.MACHINE_WORK_MODE.set(MachineWorkMode.ASYNC);
        Identifier machineId = MMCR.id("test_cube");
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controllerEntity(machineId, BlockPos.ZERO);
        RuntimeTestFixtures.formStructure(controller,
                new DynamicMachine(machineId, "async restart", new BlockArray(Map.of())));
        controller.setFormed(true);
        RuntimeTestFixtures.republish(controller);
        ServerLevel level = (ServerLevel) controller.getLevel();
        StructureClaimRegistry registry = StructureClaimRegistry.get(level);
        assertThat(registry.claim(controller.getBlockPos(), List.of()).accepted()).isTrue();

        MachineRecipe recipe = RecipeTestSupport.create(MMCR.id("async_execution_restart"), machineId, 1,
                List.of(), List.of());
        FactoryRecipeThread thread = FactoryRecipeThread.simple(controller);

        assertThat(thread.searchAndStartRecipe(List.of(recipe), 1,
                controller.runtimeSnapshot().structure().version())).isTrue();
        completeTick(controller);
        thread.tick();
        completeTick(controller);
        thread.tick();
        completeTick(controller);

        assertThat(thread.runtime().active()).isFalse();
        RuntimeTestFixtures.advanceGameTime(level);
        assertThat(thread.searchAndStartRecipe(List.of(recipe), 1,
                controller.runtimeSnapshot().structure().version())).isTrue();
        completeTick(controller);
        assertThat(thread.runtime().active()).isTrue();
    }

    @Test
    void async_finish_forwards_a_shared_io_restart_continuation() {
        AsyncExecutionContext context = new AsyncExecutionContext(new MachineAsyncCoordinator.TaskKey(BlockPos.ZERO, 0L));
        AsyncContinuation continuation = AsyncCraftingExecution.finish("base", 1L);

        AsyncContinuation.Yield.MainThread lifecycle = (AsyncContinuation.Yield.MainThread) continuation.advance(context);
        continuation = lifecycle.resume().apply(MainThreadStep.Result.success());
        AsyncContinuation.Yield.MainThread screenFlush = (AsyncContinuation.Yield.MainThread) continuation.advance(context);
        continuation = screenFlush.resume().apply(MainThreadStep.Result.success());
        AsyncContinuation.Yield.MainThread sharedIo = (AsyncContinuation.Yield.MainThread) continuation.advance(context);
        AsyncContinuation restart = ignored -> AsyncContinuation.Yield.complete();

        assertThat(sharedIo.resume().apply(MainThreadStep.Result.value(restart))).isSameAs(restart);
    }

    private static void completeTick(MachineControllerBlockEntity controller) {
        ServerLevel level = (ServerLevel) controller.getLevel();
        SharedIoCoordinator sharedIo = SharedIoCoordinator.get(level);
        sharedIo.resolve(level);
        MachineAsyncCoordinator.get(level).completeTick(() -> sharedIo.resolve(level));
    }
}
