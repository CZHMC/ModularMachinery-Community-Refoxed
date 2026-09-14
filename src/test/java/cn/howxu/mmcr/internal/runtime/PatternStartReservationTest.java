package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.MachineControllerSpec;
import cn.howxu.mmcr.api.machine.PortRequirementSpec;
import cn.howxu.mmcr.api.recipe.MachineComponent;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.component.DataComponentPredicateSet;
import cn.howxu.mmcr.api.recipe.helper.ProcessingComponent;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.internal.capability.ItemBusCapability;
import cn.howxu.mmcr.internal.storage.LongResourceStorage;
import cn.howxu.mmcr.internal.tile.FactorySchedulerBlockEntity;
import cn.howxu.mmcr.internal.tile.ItemInputBusBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.test.RecipeTestSupport;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller admission and reservation boundary tests.
 *
 * @author howxu <dev@howxu.cn>
 */
class PatternStartReservationTest {
    private static final BlockPos PATTERN_PORT = new BlockPos(3, 0, 0);

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
        TestBootstrap.bootstrapCapabilities();
    }

    @AfterEach
    void cleanup() {
        RecipeRegistry.clearForTesting();
    }

    @Test
    void inactive_or_unlinked_controllers_reject_admission() {
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"));
        MachineRecipe recipe = recipe("reservation_inactive", List.of());
        RecipeRegistry.registerStatic(recipe);

        assertThat(controller.reservePatternStart(PATTERN_PORT, List.of(), List.of()).status())
                .isEqualTo(PatternStartReservation.Status.UNAVAILABLE);

        formForPattern(controller, false);
        assertThat(controller.reservePatternStart(PATTERN_PORT, List.of(), List.of()).status())
                .isEqualTo(PatternStartReservation.Status.UNAVAILABLE);
    }

    @Test
    void normal_controller_reserves_one_slot_and_releases_it_on_rollback() {
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"));
        formForPattern(controller, true);
        MachineRecipe recipe = recipe("reservation_normal", List.of());
        RecipeRegistry.registerStatic(recipe);

        PatternStartReservation first = controller.reservePatternStart(PATTERN_PORT, List.of(), List.of());
        assertThat(first.status()).isEqualTo(PatternStartReservation.Status.RESERVED);
        assertThat(controller.reservePatternStart(PATTERN_PORT, List.of(), List.of()).status())
                .isEqualTo(PatternStartReservation.Status.UNAVAILABLE);

        first.rollback();
        assertThat(first.rolledBack()).isTrue();
        PatternStartReservation retried = controller.reservePatternStart(PATTERN_PORT, List.of(), List.of());
        assertThat(retried.commit()).isTrue();
        assertThat(retried.status()).isEqualTo(PatternStartReservation.Status.COMMITTED);
        assertThat(controller.runtimeSnapshot().crafting().recipeId()).isEqualTo(recipe.id());
    }

    @Test
    void successful_linked_controller_admissions_rotate_without_reordering_the_provider_group() {
        MachineControllerBlockEntity first = RuntimeTestFixtures.controller(MMCR.id("test_cube"));
        MachineControllerBlockEntity second = RuntimeTestFixtures.controller(MMCR.id("test_cube"));
        formForPattern(first, true);
        formForPattern(second, true);
        MachineRecipe recipe = recipe("reservation_rotation", List.of());
        RecipeRegistry.registerStatic(recipe);
        AtomicInteger cursor = new AtomicInteger();

        PatternStartReservation firstStart = MachineControllerBlockEntity.reserveNextPatternStart(
                List.of(first, second), cursor, PATTERN_PORT, List.of(), List.of());
        assertThat(firstStart.commit()).isTrue();
        PatternStartReservation secondStart = MachineControllerBlockEntity.reserveNextPatternStart(
                List.of(first, second), cursor, PATTERN_PORT, List.of(), List.of());

        assertThat(firstStart.recipe()).isEqualTo(recipe);
        assertThat(secondStart.commit()).isTrue();
        assertThat(first.runtimeSnapshot().crafting().recipeId()).isEqualTo(recipe.id());
        assertThat(second.runtimeSnapshot().crafting().recipeId()).isEqualTo(recipe.id());
    }

    @Test
    void output_matching_selects_only_the_compatible_recipe() {
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"));
        formForPattern(controller, true);
        MachineRecipe incompatible = recipe("reservation_output_incompatible", List.of(output(Items.GOLD_NUGGET, 1)));
        MachineRecipe compatible = recipe("reservation_output_compatible", List.of(output(Items.IRON_NUGGET, 1)));
        RecipeRegistry.registerStatic(incompatible);
        RecipeRegistry.registerStatic(compatible);

        PatternStartReservation reservation = controller.reservePatternStart(PATTERN_PORT,
                List.of(new MachineOutput.ItemOutput(stack(Items.IRON_NUGGET, 1), 1F)), List.of());

        assertThat(reservation.status()).isEqualTo(PatternStartReservation.Status.RESERVED);
        assertThat(reservation.recipe()).isEqualTo(compatible);
        reservation.rollback();
    }

    @Test
    void request_and_linked_input_ports_are_planned_and_committed_together() {
        ItemInputBusBlockEntity ordinaryInput = RuntimeTestFixtures.itemInput(new BlockPos(1, 0, 0));
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), ordinaryInput);
        formForPattern(controller, true);
        setItem(ordinaryInput.itemStorage(), 0, stack(Items.IRON_INGOT, 1));
        LongResourceStorage<ItemResource> requestStorage = new LongResourceStorage<>(ItemResource.class, 1, 64,
                ItemResource::isEmpty, null);
        requestStorage.setContents(0, ItemResource.of(stack(Items.IRON_INGOT, 1)), 1);
        MachineCapability request = new ItemBusCapability(requestStorage, IOType.INPUT);
        MachineRecipe recipe = recipe("reservation_atomic_inputs", List.of(input(Items.IRON_INGOT, 2)));
        RecipeRegistry.registerStatic(recipe);

        PatternStartReservation reservation = controller.reservePatternStart(PATTERN_PORT, List.of(), List.of(request));

        assertThat(reservation.status()).isEqualTo(PatternStartReservation.Status.RESERVED);
        assertThat(reservation.commit()).isTrue();
        assertThat(ordinaryInput.itemStorage().amount(0)).isZero();
        assertThat(requestStorage.amount(0)).isZero();
    }

    @Test
    void failed_request_plan_leaves_both_resources_and_the_normal_slot_available() {
        ItemInputBusBlockEntity ordinaryInput = RuntimeTestFixtures.itemInput(new BlockPos(1, 0, 0));
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), ordinaryInput);
        formForPattern(controller, true);
        setItem(ordinaryInput.itemStorage(), 0, stack(Items.IRON_INGOT, 1));
        LongResourceStorage<ItemResource> requestStorage = new LongResourceStorage<>(ItemResource.class, 1, 64,
                ItemResource::isEmpty, null);
        requestStorage.setContents(0, ItemResource.of(stack(Items.IRON_INGOT, 1)), 1);
        MachineCapability request = new ItemBusCapability(requestStorage, IOType.INPUT);
        RecipeRegistry.registerStatic(recipe("reservation_insufficient_request", List.of(input(Items.IRON_INGOT, 3))));

        assertThat(controller.reservePatternStart(PATTERN_PORT, List.of(), List.of(request)).status())
                .isEqualTo(PatternStartReservation.Status.UNAVAILABLE);
        assertThat(ordinaryInput.itemStorage().amount(0)).isEqualTo(1L);
        assertThat(requestStorage.amount(0)).isEqualTo(1L);

        MachineRecipe viable = recipe("reservation_request_retry", List.of(input(Items.IRON_INGOT, 2)));
        RecipeRegistry.registerStatic(viable);
        PatternStartReservation retried = controller.reservePatternStart(PATTERN_PORT, List.of(), List.of(request));
        assertThat(retried.recipe()).isEqualTo(viable);
        retried.rollback();
    }

    @Test
    void factory_reserves_a_concrete_idle_lane_until_commit_or_rollback() {
        MachineControllerBlockEntity controller = factoryController();
        controller.componentRuntime().replaceLinkedPortPositions(Set.of(PATTERN_PORT));
        RuntimeTestFixtures.republish(controller);
        assertThat(controller.hasFactoryController()).isTrue();
        assertThat(controller.runtimeSnapshot().linkedPortPositions()).contains(PATTERN_PORT);
        MachineRecipe recipe = RecipeTestSupport.create(MMCR.id("reservation_factory"), MMCR.id("reservation_factory"),
                20, List.of(), List.of());
        RecipeRegistry.registerStatic(recipe);

        PatternStartReservation first = controller.reservePatternStart(PATTERN_PORT, List.of(), List.of());
        assertThat(first.status()).isEqualTo(PatternStartReservation.Status.RESERVED);
        assertThat(first.laneId()).isEqualTo("base");
        assertThat(controller.reservePatternStart(PATTERN_PORT, List.of(), List.of()).status())
                .isEqualTo(PatternStartReservation.Status.UNAVAILABLE);

        first.rollback();
        PatternStartReservation retried = controller.reservePatternStart(PATTERN_PORT, List.of(), List.of());
        assertThat(retried.commit()).isTrue();
        assertThat(retried.laneId()).isEqualTo("base");
    }

    private static void formForPattern(MachineControllerBlockEntity controller, boolean linked) {
        controller.setFormed(true);
        controller.componentRuntime().replaceLinkedPortPositions(linked ? Set.of(PATTERN_PORT) : Set.of());
        RuntimeTestFixtures.republish(controller);
    }

    private static MachineRecipe recipe(String path, List<ItemRequirement> requirements) {
        return RecipeTestSupport.create(MMCR.id(path), MMCR.id("test_cube"), 20,
                List.of(), List.of(), List.of(), 0, 1, false, List.of(), requirements);
    }

    private static ItemRequirement input(Item item, int count) {
        return new ItemRequirement(RecipeModifier.IOType.INPUT, Ingredient.of(item), count, ItemStack.EMPTY);
    }

    private static ItemRequirement output(Item item, int count) {
        return new ItemRequirement(RecipeModifier.IOType.OUTPUT, null, 0, stack(item, count),
                1F, List.of(), DataComponentPredicateSet.EMPTY, 1F);
    }

    private static ItemStack stack(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        stack.set(DataComponents.MAX_STACK_SIZE, 64);
        return stack;
    }

    private static void setItem(cn.howxu.mmcr.api.capability.storage.ResourceStorage<ItemResource> storage,
                                int slot, ItemStack stack) {
        try (Transaction transaction = Transaction.openRoot()) {
            ItemResource current = storage.resource(slot);
            if (current != null && !current.isEmpty()) storage.extract(slot, current, storage.amount(slot), transaction);
            storage.insert(slot, ItemResource.of(stack), stack.getCount(), transaction);
            transaction.commit();
        }
    }

    private static MachineControllerBlockEntity factoryController() {
        Identifier machineId = MMCR.id("reservation_factory");
        RuntimeTestFixtures.registerRecipePool(machineId);
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controllerEntity(MMCR.id("test_cube"), BlockPos.ZERO);
        BlockPos schedulerPos = controller.getBlockPos().offset(-1, 0, 0);
        BlockArray pattern = new BlockArray(Map.of(new BlockPos(1, 0, 0),
                new BlockPredicate.OfBlock(ModBlocks.BLOCKS.get("factory_controller").get())));
        DynamicMachine machine = new DynamicMachine(machineId, "reservation factory", pattern,
                MachineControllerSpec.defaultsFor(machineId), PortRequirementSpec.none(), List.of(), Map.of(),
                1, false, true, 1);
        FactorySchedulerBlockEntity scheduler = new FactorySchedulerBlockEntity(schedulerPos,
                ModBlocks.BLOCKS.get("factory_controller").get().defaultBlockState());
        RuntimeTestFixtures.formStructureWithComponents(controller, machine, scheduler);
        controller.componentRuntime().replaceComponents(List.of(
                new ProcessingComponent(null, scheduler, scheduler.getBlockPos(), BlockPos.ZERO, (String) null)));
        controller.setFormed(true);
        RuntimeTestFixtures.republish(controller);
        return controller;
    }

}
