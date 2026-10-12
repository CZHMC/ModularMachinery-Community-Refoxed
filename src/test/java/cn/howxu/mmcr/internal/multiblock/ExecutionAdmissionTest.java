package cn.howxu.mmcr.internal.multiblock;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.config.ServerConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.fml.config.IConfigSpec;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.recipe.MachineComponent;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.helper.ProcessingComponent;
import cn.howxu.mmcr.api.recipe.requirement.EnergyRequirement;
import cn.howxu.mmcr.internal.async.MachineAsyncCoordinator;
import cn.howxu.mmcr.internal.recipe.MachineRecipeThread;
import cn.howxu.mmcr.internal.runtime.CraftingRuntime;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import cn.howxu.mmcr.internal.runtime.RecipeExecutionState.Receipt;
import cn.howxu.mmcr.internal.tile.EnergyInputHatchBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.test.ConfigTestSupport;
import cn.howxu.mmcr.test.RecipeTestSupport;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.ServerThreadTestScope;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Accepted-start receipts and retired-lane backpressure with deliberately stopped workers.
 *
 * @author howxu <dev@howxu.cn>
 */
class ExecutionAdmissionTest {
    private ServerThreadTestScope serverThreadScope;
    private ServerLevel level;
    private MachineControllerBlockEntity controller;
    private EnergyInputHatchBlockEntity input;
    private SharedIoCoordinator shared;
    private MachineAsyncCoordinator async;
    private MachineRecipe recipe;
    private final ArrayDeque<Runnable> workers = new ArrayDeque<>();

    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrap();
        CommentedConfig config = CommentedConfig.inMemory();
        ServerConfig.SPEC.correct(config);
        var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ServerConfig.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null));
    }

    @BeforeEach
    void setup() throws Exception {
        TestBootstrap.bootstrapCapabilities();
        serverThreadScope = ServerThreadTestScope.open();
        RecipeRegistry.clearForTesting();
        ConfigTestSupport.setMachineWorkMode(MachineWorkMode.ASYNC);
        input = RuntimeTestFixtures.energyInput(new BlockPos(1, 0, 0));
        controller = RuntimeTestFixtures.controllerEntity(MMCR.id("test_cube"), BlockPos.ZERO);
        RuntimeTestFixtures.formStructureWithComponents(controller, new DynamicMachine(MMCR.id("test_cube"),
                "execution admission", new BlockArray(Map.of())), input);
        controller.componentRuntime().replaceComponents(List.of(new ProcessingComponent(
                new MachineComponent(input.kind(), input.ioType()), input, input.getBlockPos(), input.getBlockPos(), (String) null)));
        RuntimeTestFixtures.republish(controller);
        level = (ServerLevel) controller.getLevel();
        assertThat(StructureClaimRegistry.get(level).claim(controller.getBlockPos(), List.of()).accepted()).isTrue();
        shared = new SharedIoCoordinator(16, 2);
        async = MachineAsyncCoordinator.forTesting(workers::addLast);
        installCoordinator(SharedIoCoordinator.class, shared);
        installCoordinator(MachineAsyncCoordinator.class, async);
        RuntimeTestFixtures.beginLevelTick(level, true);
        controller.serverTick();
        input.getEnergyStorage().setAmount(1_000L);
        recipe = RecipeTestSupport.create(MMCR.id("execution_admission"), MMCR.id("test_cube"), 40,
                List.of(new EnergyRequirement(7L)), List.of());
    }

    @AfterEach
    void cleanup() throws Exception {
        try {
            if (level != null) {
                SharedIoCoordinator.discard(level);
                MachineAsyncCoordinator.discard(level);
                StructureClaimRegistry.discard(level);
            }
            RecipeRegistry.clearForTesting();
            ConfigTestSupport.setMachineWorkMode(MachineWorkMode.ASYNC);
        } finally {
            if (serverThreadScope != null) serverThreadScope.close();
        }
    }

    @Test
    void cancel_before_compilation_keeps_both_paid_starts_until_receipt_ack() throws Exception {
        CraftingRuntime runtime = RuntimeTestFixtures.craftingRuntime(controller);
        MachineRecipeThread thread = new MachineRecipeThread(controller, runtime);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(thread.searchAndStartRecipe(List.of(recipe), 1L,
                    controller.currentStructureSnapshot().version())).isTrue();
            // Drive only start work. Stop immediately when the input commit queues compilation.
            for (int step = 0; step < 32 && !runtime.active(); step++) {
                if (!workers.isEmpty()) workers.removeFirst().run();
                async.pumpMainThreadSteps();
                shared.resolve(level);
            }
            assertThat(runtime.active()).isTrue();
            assertThat(input.getEnergyStorage().amount()).isEqualTo(1_000L - 7L * (attempt + 1));
            assertThat(runtime.installedProgram()).isNull();
            assertThat(workers).isNotEmpty();
            Field tasksField = MachineAsyncCoordinator.class.getDeclaredField("tasks");
            tasksField.setAccessible(true);
            assertThat(((Map<?, ?>) tasksField.get(async)).containsKey(new MachineAsyncCoordinator.TaskKey(
                    controller.getBlockPos(), level.getGameTime(), MachineWorkMode.ASYNC, "program/base",
                    controller.lifecycleEpoch()))).isTrue();
            Receipt admitted = shared.receiptsForTesting(runtime, "base").stream()
                    .filter(receipt -> !receipt.terminal()).findFirst().orElseThrow();
            thread.invalidate();
            // Input commit resumes the start continuation, but its TaskKey is still live
            // while workers are stopped. Cancel/drain that task before reusing this tick's
            // key; do not run compilation or receipt workers or acknowledge paid terminals.
            async.cancel(controller.getBlockPos());
            async.pumpMainThreadSteps();
            assertThat(shared.receiptsForTesting(runtime, "base")).anySatisfy(receipt -> {
                assertThat(receipt.identity()).isEqualTo(admitted.identity());
                assertThat(receipt.terminal()).isTrue();
                assertThat(receipt.sequence()).isGreaterThan(admitted.sequence());
                assertThat(receipt.paidAttempts()).isEqualTo(1L);
            });
        }
        assertThat(shared.receiptsForTesting(runtime, "base")).hasSize(2).allSatisfy(receipt ->
                assertThat(receipt.terminal()).isTrue());
        assertThat(shared.reserveExecution(runtime, "base")).isFalse();
        long paid = input.getEnergyStorage().amount();
        drainWorkersAndAcks();
        assertThat(runtime.installedProgram()).isNull();
        assertThat(input.getEnergyStorage().amount()).isEqualTo(paid);
        assertThat(shared.receiptsForTesting(runtime, "base")).isEmpty();
        assertThat(shared.reserveExecution(runtime, "base")).isTrue();
    }

    @Test
    void accepted_identity_is_reused_when_compilation_installs() {
        CraftingRuntime runtime = RuntimeTestFixtures.craftingRuntime(controller);
        assertThat(runtime.start(recipe, 1L).isCrafting()).isTrue();
        Receipt admitted = shared.receiptsForTesting(runtime, "base").getFirst();
        var program = runtime.ensureInstalledProgram();
        assertThat(program.identity()).isEqualTo(admitted.identity());
        assertThat(shared.install(program, shared.controllerPermit(controller.getBlockPos()))).isTrue();
        assertThat(shared.receiptsForTesting(runtime, "base")).singleElement().satisfies(receipt -> {
            assertThat(receipt.identity()).isEqualTo(admitted.identity());
            assertThat(receipt.sequence()).isGreaterThan(admitted.sequence());
        });
    }

    @Test
    void retiring_new_lane_ids_reuses_logical_capacity_and_ack_restores_admission() throws Exception {
        List<CraftingRuntime> retired = new ArrayList<>();
        for (int lane = 0; lane < 2; lane++) {
            CraftingRuntime runtime = lane("factory-" + lane);
            assertThat(runtime.start(recipe, 1L).isCrafting()).isTrue();
            shared.forgetExecutionLane(runtime, "factory-" + lane);
            runtime.invalidate();
            retired.add(runtime);
        }
        long paid = input.getEnergyStorage().amount();
        for (int lane = 2; lane < 32; lane++) {
            CraftingRuntime replacement = lane("factory-" + lane);
            assertThat(shared.reserveExecution(replacement, "factory-" + lane)).isFalse();
            replacement.start(recipe, 1L);
            assertThat(replacement.active()).isFalse();
            shared.forgetExecutionLane(replacement, "factory-" + lane);
            assertThat(recordCount()).isEqualTo(1);
        }
        assertThat(input.getEnergyStorage().amount()).isEqualTo(paid);
        for (int lane = 0; lane < 2; lane++) {
            assertThat(shared.receiptsForTesting(retired.get(lane), "factory-" + lane))
                    .singleElement().satisfies(receipt -> assertThat(receipt.terminal()).isTrue());
        }
        drainWorkersAndAcks();
        assertThat(recordCount()).isZero();
        CraftingRuntime replacement = lane("factory-32");
        assertThat(replacement.start(recipe, 1L).isCrafting()).isTrue();
        assertThat(input.getEnergyStorage().amount()).isEqualTo(paid - 7L);
    }

    @Test
    void replacing_the_be_at_the_same_position_cannot_bypass_unacknowledged_lane_capacity() throws Exception {
        CraftingRuntime old = lane("factory-0");
        assertThat(old.start(recipe, 1L).isCrafting()).isTrue();
        shared.forgetExecutionLane(old, "factory-0");
        old.invalidate();
        MachineControllerBlockEntity replacement = RuntimeTestFixtures.controllerEntity(MMCR.id("test_cube"), BlockPos.ZERO);
        RuntimeTestFixtures.formStructure(replacement, new DynamicMachine(MMCR.id("test_cube"),
                "replacement admission", new BlockArray(Map.of())));
        ServerLevel temporaryLevel = (ServerLevel) replacement.getLevel();
        RuntimeTestFixtures.attachLevel(replacement, level);
        RuntimeTestFixtures.replaceBlockEntity(replacement, replacement);
        replacement.componentRuntime().replaceComponents(controller.componentRuntime().components());
        RuntimeTestFixtures.republish(replacement);
        shared.markControllerEligible(replacement, level.getGameTime());
        try {
            CraftingRuntime next = new CraftingRuntime(replacement, replacement.componentRuntime());
            next.bindRecipeLane("factory-100", () -> { });
            assertThat(next.start(recipe, 1L).isCrafting()).isTrue();
            Receipt nextReceipt = shared.receiptsForTesting(next, "factory-100").getFirst();
            Receipt oldReceipt = shared.receiptsForTesting(old, "factory-0").getFirst();
            assertThat(nextReceipt.identity().controllerInstance()).isNotEqualTo(oldReceipt.identity().controllerInstance());
            shared.forgetExecutionLane(next, "factory-100");
            next.invalidate();
            CraftingRuntime blocked = new CraftingRuntime(replacement, replacement.componentRuntime());
            blocked.bindRecipeLane("factory-200", () -> { });
            long paid = input.getEnergyStorage().amount();
            assertThat(blocked.start(recipe, 1L).isCrafting()).isFalse();
            assertThat(input.getEnergyStorage().amount()).isEqualTo(paid);
            assertThat(recordCount()).isEqualTo(1);
            drainWorkersAndAcks();
            assertThat(blocked.start(recipe, 1L).isCrafting()).isTrue();
            assertThat(input.getEnergyStorage().amount()).isEqualTo(paid - 7L);
        } finally {
            SharedIoCoordinator.discard(temporaryLevel);
            MachineAsyncCoordinator.discard(temporaryLevel);
            StructureClaimRegistry.discard(temporaryLevel);
        }
    }

    @Test
    void cancelled_input_transaction_rolls_back_and_releases_unpaid_proposal() {
        CraftingRuntime runtime = lane("rollback");
        var prepared = runtime.preparePatternStart(recipe, 1L, List.of());
        assertThat(prepared).isNotNull();
        assertThat(runtime.reservePatternStart()).isTrue();
        assertThatThrownBy(() -> runtime.commitPatternStart(prepared, transaction -> {
            shared.cancel(controller.getBlockPos());
            throw new IllegalStateException("abort input transaction");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(input.getEnergyStorage().amount()).isEqualTo(1_000L);
        assertThat(shared.receiptsForTesting(runtime, "rollback")).isEmpty();
        assertThat(runtime.active()).isFalse();
        assertThat(shared.reserveExecution(runtime, "rollback")).isTrue();
    }

    @Test
    void cancel_during_input_commit_keeps_paid_terminal_even_before_runtime_activation() {
        CraftingRuntime runtime = lane("commit-cancel");
        var prepared = runtime.preparePatternStart(recipe, 1L, List.of());
        assertThat(prepared).isNotNull();
        assertThat(runtime.reservePatternStart()).isTrue();
        assertThat(runtime.commitPatternStart(prepared, transaction -> {
            shared.cancel(controller.getBlockPos());
            runtime.invalidate();
        })).isFalse();
        assertThat(runtime.active()).isFalse();
        assertThat(input.getEnergyStorage().amount()).isEqualTo(993L);
        assertThat(shared.receiptsForTesting(runtime, "commit-cancel")).singleElement().satisfies(receipt -> {
            assertThat(receipt.terminal()).isTrue();
            assertThat(receipt.paidAttempts()).isEqualTo(1L);
        });
        drainWorkersAndAcks();
        assertThat(shared.receiptsForTesting(runtime, "commit-cancel")).isEmpty();
    }

    private CraftingRuntime lane(String laneId) {
        CraftingRuntime runtime = new CraftingRuntime(controller, controller.componentRuntime());
        runtime.bindRecipeLane(laneId, () -> { });
        return runtime;
    }

    private void drainWorkersAndAcks() {
        async.pumpReceiptDrains();
        while (!workers.isEmpty()) workers.removeFirst().run();
        async.pumpMainThreadSteps();
        shared.drainReceiptAcknowledgements(level);
    }

    private int recordCount() throws Exception {
        Field field = SharedIoCoordinator.class.getDeclaredField("executionRecords");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(shared)).size();
    }

    @SuppressWarnings("unchecked")
    private void installCoordinator(Class<?> type, Object coordinator) throws Exception {
        Field field = type.getDeclaredField("COORDINATORS");
        field.setAccessible(true);
        ((Map<ServerLevel, Object>) field.get(null)).put(level, coordinator);
    }
}
