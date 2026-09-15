package cn.howxu.mmcr.internal.async;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.MachineControllerSpec;
import cn.howxu.mmcr.api.machine.PortRequirementSpec;
import cn.howxu.mmcr.api.recipe.MachineComponent;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.helper.ProcessingComponent;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.config.Config;
import cn.howxu.mmcr.internal.event.SharedIoEvents;
import cn.howxu.mmcr.internal.multiblock.SharedIoCoordinator;
import cn.howxu.mmcr.internal.multiblock.StructureClaimRegistry;
import cn.howxu.mmcr.internal.runtime.FactoryRuntime;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import cn.howxu.mmcr.internal.runtime.ResourceAvailabilityNotifier.Reason;
import cn.howxu.mmcr.internal.tile.FactorySchedulerBlockEntity;
import cn.howxu.mmcr.internal.tile.ItemOutputBusBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerRuntime;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.test.RecipeTestSupport;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.TestBootstrap;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies factory lane scheduling through the asynchronous execution chain.
 *
 * @author howxu <dev@howxu.cn>
 */
class AsyncFactoryExecutionTest {
    private ServerLevel level;

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
        CommentedConfig config = CommentedConfig.inMemory();
        Config.SERVER_SPEC.correct(config);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Config.SERVER_SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null));
    }

    @AfterEach
    void cleanup() {
        Config.MACHINE_WORK_MODE.set(MachineWorkMode.ASYNC);
        RecipeRegistry.clearForTesting();
        if (level == null) return;
        MachineAsyncCoordinator.discard(level);
        SharedIoCoordinator.discard(level);
        StructureClaimRegistry.discard(level);
    }

    @Test
    void async_factory_advances_each_active_lane_once_after_same_tick_shared_io_grants() {
        MachineControllerBlockEntity controller = factoryController();
        MachineRecipe recipe = RecipeTestSupport.create(MMCR.id("async_factory_lanes"), MMCR.id("test_cube"), 20,
                List.of(), List.of(), List.of(), 0, 2);
        RecipeRegistry.registerStatic(recipe);
        Config.MACHINE_WORK_MODE.set(MachineWorkMode.ASYNC);

        controller.serverTick();

        assertThat(controller.runtimeSnapshot().factory().activeLaneCount()).isZero();

        SharedIoEvents.completeLevelTick(level);

        assertThat(controller.runtimeSnapshot().factory().activeLaneCount()).isEqualTo(2);

        RuntimeTestFixtures.advanceGameTime(level);
        controller.serverTick();
        SharedIoEvents.completeLevelTick(level);

        assertThat(controller.runtimeSnapshot().factory().presentationLanes())
                .filteredOn(lane -> lane.active())
                .extracting(lane -> lane.tick())
                .containsOnly(1);
    }

    @Test
    void cancelled_factory_search_does_not_commit_and_resumes_only_after_the_pause_clears() {
        MachineControllerBlockEntity controller = factoryController();
        MachineRecipe recipe = RecipeTestSupport.create(MMCR.id("cancelled_async_factory_search"), MMCR.id("test_cube"), 20,
                List.of(), List.of(), List.of(), 0, 2);
        RecipeRegistry.registerStatic(recipe);
        Config.MACHINE_WORK_MODE.set(MachineWorkMode.ASYNC);

        controller.serverTick();
        RuntimeTestFixtures.setDirectSignal(level, controller.getBlockPos(), 15);
        controller.serverTick();
        SharedIoEvents.completeLevelTick(level);

        assertThat(controller.runtimeSnapshot().factory().activeLaneCount()).isZero();

        RuntimeTestFixtures.setDirectSignal(level, controller.getBlockPos(), 0);
        RuntimeTestFixtures.advanceGameTime(level);
        controller.serverTick();
        SharedIoEvents.completeLevelTick(level);

        assertThat(controller.runtimeSnapshot().factory().activeLaneCount()).isEqualTo(2);
    }

    @Test
    void shrinking_factory_invalidates_queued_lane_searches_before_their_continuations_commit() {
        MachineControllerBlockEntity controller = factoryController();
        MachineRecipe recipe = RecipeTestSupport.create(MMCR.id("shrunk_async_factory_search"), MMCR.id("test_cube"), 20,
                List.of(), List.of(), List.of(), 0, 2);
        RecipeRegistry.registerStatic(recipe);
        Config.MACHINE_WORK_MODE.set(MachineWorkMode.ASYNC);

        controller.serverTick();
        factoryRuntime(controller).setLaneLimit(1);
        SharedIoEvents.completeLevelTick(level);

        assertThat(controller.runtimeSnapshot().factory().presentationLanes()).hasSize(1);
        assertThat(controller.runtimeSnapshot().factory().activeLaneCount()).isEqualTo(1);
    }

    @Test
    void shared_output_release_wakes_an_async_failed_factory_lane_before_backoff_expires() {
        ItemOutputBusBlockEntity output = RuntimeTestFixtures.itemOutput(new BlockPos(2, 0, 0));
        MachineControllerBlockEntity controller = factoryController(output);
        MachineRecipe recipe = RecipeTestSupport.create(MMCR.id("async_factory_output_wakeup"), MMCR.id("test_cube"), 20,
                List.of(), List.of(), List.of(), 0, 1, false, List.of(), List.of(
                new ItemRequirement(RecipeModifier.IOType.OUTPUT, null, 0,
                        new ItemStack(Items.IRON_INGOT, 1))));
        RecipeRegistry.registerStatic(recipe);
        Config.MACHINE_WORK_MODE.set(MachineWorkMode.ASYNC);
        factoryRuntime(controller).setLaneLimit(1);
        for (int slot = 0; slot < output.itemStorage().size(); slot++) {
            try (Transaction transaction = Transaction.openRoot()) {
                output.itemStorage().insert(slot, ItemResource.of(Items.COBBLESTONE), 64, transaction);
                transaction.commit();
            }
        }

        controller.serverTick();
        SharedIoEvents.completeLevelTick(level);
        assertThat(controller.runtimeSnapshot().factory().activeLaneCount()).isZero();
        assertThat(controller.runtimeSnapshot().factory().presentationLanes().getFirst().lastFailureUnloc())
                .isEqualTo("gui.mmcr.controller.failure.missing_output");

        RuntimeTestFixtures.advanceGameTime(level);
        try (Transaction transaction = Transaction.openRoot()) {
            output.itemStorage().extract(0, ItemResource.of(Items.COBBLESTONE), 64, transaction);
            transaction.commit();
        }
        controller.notifyResourceAvailability(Reason.OUTPUT_CAPACITY, null);
        controller.serverTick();
        SharedIoEvents.completeLevelTick(level);

        assertThat(controller.runtimeSnapshot().factory().activeLaneCount()).isEqualTo(1);
        assertThat(factoryRuntime(controller).searchAttemptsForTesting()).isGreaterThan(1L);
    }

    private MachineControllerBlockEntity factoryController() {
        return factoryController(null);
    }

    private MachineControllerBlockEntity factoryController(ItemOutputBusBlockEntity output) {
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controllerEntity(MMCR.id("test_cube"), BlockPos.ZERO);
        BlockPos schedulerPos = controller.getBlockPos().offset(-1, 0, 0);
        BlockArray pattern = new BlockArray(Map.of(new BlockPos(1, 0, 0),
                new BlockPredicate.OfBlock(ModBlocks.BLOCKS.get("factory_controller").get())));
        DynamicMachine machine = new DynamicMachine(MMCR.id("test_cube"), "async factory", pattern,
                MachineControllerSpec.defaultsFor(MMCR.id("test_cube")), PortRequirementSpec.none(), List.of(), Map.of(),
                1, false, true, 2);
        FactorySchedulerBlockEntity scheduler = new FactorySchedulerBlockEntity(schedulerPos,
                ModBlocks.BLOCKS.get("factory_controller").get().defaultBlockState());
        if (output == null) {
            RuntimeTestFixtures.formStructureWithComponents(controller, machine, scheduler);
        } else {
            RuntimeTestFixtures.formStructureWithComponents(controller, machine, scheduler, output);
        }
        List<ProcessingComponent> components = new ArrayList<>();
        components.add(new ProcessingComponent(null, scheduler, scheduler.getBlockPos(), BlockPos.ZERO, (String) null));
        if (output != null) {
            components.add(new ProcessingComponent(new MachineComponent(output.kind(), output.ioType()), output,
                    output.getBlockPos(), output.getBlockPos(), (String) null));
            output.linkControllerAppearance(controller.getBlockPos(), null);
        }
        controller.componentRuntime().replaceComponents(components);
        controller.setFormed(true);
        RuntimeTestFixtures.republish(controller);
        level = (ServerLevel) controller.getLevel();
        assertThat(StructureClaimRegistry.get(level).claim(controller.getBlockPos(), List.of()).accepted()).isTrue();
        return controller;
    }

    private static FactoryRuntime factoryRuntime(MachineControllerBlockEntity controller) {
        try {
            var field = MachineControllerBlockEntity.class.getDeclaredField("runtime");
            field.setAccessible(true);
            return ((MachineControllerRuntime) field.get(controller)).factoryRuntime();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to access controller factory runtime", exception);
        }
    }
}
