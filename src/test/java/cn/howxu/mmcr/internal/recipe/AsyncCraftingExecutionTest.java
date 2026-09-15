package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.internal.multiblock.SharedIoCoordinator;
import cn.howxu.mmcr.internal.multiblock.StructureClaimRegistry;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.test.RecipeTestSupport;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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
    }

    @Test
    void finishing_tick_commits_its_continuation_in_the_same_shared_io_fence() {
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
        resolve(controller);
        long epochBeforeFinish = controller.resourceAvailabilityEpoch();
        thread.tick();
        resolve(controller);

        assertThat(thread.runtime().active()).isFalse();
        assertThat(finishes).hasValue(1);
        assertThat(controller.resourceAvailabilityEpoch()).isEqualTo(epochBeforeFinish + 1L);
    }

    private static void resolve(MachineControllerBlockEntity controller) {
        SharedIoCoordinator.get((ServerLevel) controller.getLevel()).resolve(controller.resourceDomain());
    }
}
