package cn.howxu.mmcr.compat.appliedenergistics2;

import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier.IOType;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.ItemDefinition;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.items.misc.MissingContentItem;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.LevelStub;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.MachineControllerSpec;
import cn.howxu.mmcr.api.machine.PortRequirementSpec;
import cn.howxu.mmcr.api.recipe.MachineComponent;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.component.DataComponentPredicateSet;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternInterfaceCraftingMachine;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.PatternInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.internal.block.IOPortBlock;
import cn.howxu.mmcr.internal.runtime.PatternStartReservation;
import cn.howxu.mmcr.api.recipe.helper.ProcessingComponent;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.registry.ModBlockEntities;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.TestBootstrap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import com.mojang.serialization.Lifecycle;
import java.lang.reflect.Field;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the AE2 crafting-machine bridge for MMCR pattern interfaces.
 *
 * @author howxu <dev@howxu.cn>
 */
class PatternInterfaceCraftingMachineTest {
    private static final BlockPos PATTERN_PORT = new BlockPos(3, 0, 0);

    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
        TestBootstrap.bootstrapCapabilities();
        if (!ae2KeyTypesAreInitialized()) initializeAE2KeyTypes();
        bindMissingContentItem();
        bindTestPatternInterfaceEntityType();
        bindTestPatternInterfaceBlock();
    }

    @AfterEach
    void cleanup() {
        RecipeRegistry.clearForTesting();
    }

    @Test
    void rejects_unrepresentable_single_outputs_without_taking_material() {
        ItemStack definition = new ItemStack(Items.PAPER);
        AEProcessingPattern.encode(definition, List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)),
                List.of(new GenericStack(AEItemKey.of(Items.IRON_NUGGET), (long) Integer.MAX_VALUE + 1L)));
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        RecipeRegistry.registerStatic(recipe("bridge_unrepresentable_output", Items.IRON_NUGGET, 2));
        var machine = new PatternInterfaceCraftingMachine(host);
        KeyCounter request = counter(Items.IRON_INGOT, 1L);
        assertThat(machine.pushPattern(new AEProcessingPattern(AEItemKey.of(definition)),
                new KeyCounter[]{request}, null)).isFalse();
        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isOne();
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
    }

    @Test
    void rejects_nonstandard_input_amounts_and_slot_layout_without_taking_material() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        RecipeRegistry.registerStatic(recipe("bridge_invalid_inputs", Items.IRON_NUGGET, 2));
        var machine = new PatternInterfaceCraftingMachine(host);
        var pattern = processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2);
        for (long amount : new long[]{1L, 4L, 6L, Long.MAX_VALUE}) {
            KeyCounter request = counter(Items.IRON_INGOT, amount);
            assertThat(machine.pushPattern(pattern, new KeyCounter[]{request}, null)).isFalse();
            assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(amount);
        }
        KeyCounter request = counter(Items.IRON_INGOT, 5L);
        assertThat(machine.pushPattern(pattern, new KeyCounter[]{request, new KeyCounter()}, null)).isFalse();
        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
    }

    @Test
    void rejects_actual_keys_with_different_components_without_taking_material() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        RecipeRegistry.registerStatic(recipe("bridge_actual_components", Items.IRON_NUGGET, 2));
        ItemStack namedIron = new ItemStack(Items.IRON_INGOT);
        namedIron.set(DataComponents.CUSTOM_NAME, Component.literal("not the requested key"));
        AEItemKey actual = AEItemKey.of(namedIron);
        KeyCounter request = new KeyCounter();
        request.add(actual, 5L);
        assertThat(host.craftingMachine().pushPattern(processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2),
                new KeyCounter[]{request}, null)).isFalse();
        assertThat(request.get(actual)).isEqualTo(5L);
        assertThat(controller.runtimeSnapshot().crafting().recipeId()).isNull();
        assertThat(host.getLogic().getReturnInv().isEmpty()).isTrue();
    }

    @Test
    void rejects_unlinked_and_output_incompatible_patterns_without_taking_requested_material() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        formForPattern(controller, false);
        RecipeRegistry.registerStatic(recipe("bridge_reject", Items.IRON_NUGGET, 2));
        KeyCounter request = counter(Items.IRON_INGOT, 5L);

        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(
                processingPattern(Items.IRON_INGOT, Items.GOLD_NUGGET, 2), new KeyCounter[]{request}, null)).isFalse();

        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.getLogic().getReturnInv().isEmpty()).isTrue();
        assertThat(controller.reservePatternStart(PATTERN_PORT, List.of(), List.of()).status())
                .isEqualTo(PatternStartReservation.Status.UNAVAILABLE);

        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(
                processingPattern(Items.IRON_INGOT, Items.GOLD_NUGGET, 2), new KeyCounter[]{request}, null)).isFalse();
        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.getLogic().getReturnInv().isEmpty()).isTrue();
    }

    @Test
    void pushOwnsOnlyOneImmediatelyPaidBatch() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        MachineRecipe recipe = recipe("bridge_accept", Items.IRON_NUGGET, 2);
        RecipeRegistry.registerStatic(recipe);
        KeyCounter request = counter(Items.IRON_INGOT, 5L);

        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(
                processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2), new KeyCounter[]{request}, null)).isTrue();

        assertThat(controller.runtimeSnapshot().crafting().recipeId()).isEqualTo(recipe.id());
        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isZero();
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
        assertThat(host.getLogic().getReturnInv().getStack(0))
                .isEqualTo(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 3L));
        KeyCounter next = counter(Items.IRON_INGOT, 5L);
        assertThat(host.craftingMachine().pushPattern(processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2),
                new KeyCounter[]{next}, null)).isFalse();
        assertThat(next.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.getLogic().getReturnInv().getStack(0))
                .isEqualTo(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 3L));
    }

    @Test
    void busy_controller_rejects_other_definitions_without_taking_material() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        controller.setMachine(parallelMachine(10));
        controller.componentRuntime().replaceModifiers(Map.of("parallel", List.of(
                MachineModifier.numeric("parallelism", "machine", 9D, "add", false))));
        formForPattern(controller, true);
        RecipeRegistry.registerStatic(recipe("bridge_nonparallel_total", Items.IRON_NUGGET, 2));
        var first = processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2);
        ItemStack secondDefinition = first.getDefinition().toStack(1);
        secondDefinition.set(DataComponents.CUSTOM_NAME, Component.literal("second request group"));
        var second = new AEProcessingPattern(AEItemKey.of(secondDefinition));
        assertThat(host.craftingMachine().pushPattern(first, new KeyCounter[]{counter(Items.IRON_INGOT, 5L)}, null)).isTrue();
        var rejected = counter(Items.IRON_INGOT, 5L);
        assertThat(host.craftingMachine().pushPattern(second, new KeyCounter[]{rejected}, null)).isFalse();
        assertThat(rejected.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
    }

    @Test
    void bare_pattern_does_not_expand_into_spare_parallel_capacity() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        controller.setMachine(parallelMachine(10));
        controller.componentRuntime().replaceModifiers(Map.of("parallel", List.of(
                MachineModifier.numeric("parallelism", "machine", 9D, "add", false))));
        formForPattern(controller, true);
        var base = recipe("bridge_partial_parallel", Items.IRON_NUGGET, 2);
        RecipeRegistry.registerStatic(MachineRecipe.fromCanonical(base.id(), base.recipePoolId(), 20,
                base.requirements(), base.machineOutputs(), List.of(), 0, 1, false, true, false, Set.of()));
        var pattern = processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2);
        assertThat(host.craftingMachine().pushPattern(pattern,
                new KeyCounter[]{counter(Items.IRON_INGOT, 5L)}, null)).isTrue();
        assertThat(controller.runtimeSnapshot().crafting().parallelism()).isOne();
        controller.componentRuntime().replaceModifiers(Map.of("parallel", List.of(
                MachineModifier.numeric("parallelism", "machine", 2D, "add", false))));
        formForPattern(controller, true);
        var rejected = counter(Items.IRON_INGOT, 5L);
        assertThat(host.craftingMachine().pushPattern(pattern, new KeyCounter[]{rejected}, null)).isFalse();
        assertThat(rejected.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(controller.runtimeSnapshot().crafting().parallelism()).isOne();
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
        assertThat(host.getLogic().getReturnInv().getStack(0))
                .isEqualTo(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 3L));
    }

    private static DynamicMachine parallelMachine(long parallelism) {
        var id = MMCR.id("test_cube");
        return new DynamicMachine(id, "runtime test", new BlockArray(Map.of()),
                MachineControllerSpec.defaultsFor(id), PortRequirementSpec.none(), List.of(), Map.of(),
                parallelism, true, false, 1);
    }

    @Test
    void distributes_two_standard_requests_across_linked_controllers_without_losing_material() {
        PatternInterfaceBlockEntity host = patternHost();
        List<MachineControllerBlockEntity> controllers = linkedControllers(host);
        MachineControllerBlockEntity first = controllers.get(0);
        MachineControllerBlockEntity second = controllers.get(1);
        host.linkControllerAppearance(first.getBlockPos(), null);
        host.linkControllerAppearance(second.getBlockPos(), null);
        formForPattern(first, true);
        formForPattern(second, true);
        MachineRecipe recipe = recipe("bridge_batch", Items.IRON_NUGGET, 2);
        RecipeRegistry.registerStatic(recipe);
        KeyCounter request = counter(Items.IRON_INGOT, 5L);
        KeyCounter nextRequest = counter(Items.IRON_INGOT, 5L);
        var machine = new PatternInterfaceCraftingMachine(host);
        var pattern = processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2);
        assertThat(machine.pushPattern(pattern, new KeyCounter[]{request}, null)).isTrue();
        assertThat(machine.pushPattern(pattern, new KeyCounter[]{nextRequest}, null)).isTrue();
        assertThat(first.runtimeSnapshot().crafting().recipeId()).isEqualTo(recipe.id());
        assertThat(second.runtimeSnapshot().crafting().recipeId()).isEqualTo(recipe.id());
        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isZero();
        assertThat(nextRequest.get(AEItemKey.of(Items.IRON_INGOT))).isZero();
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
        assertThat(host.getLogic().getReturnInv().getStack(0))
                .isEqualTo(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 6L));
    }

    @Test
    void rejects_non_processing_patterns_without_taking_requested_material() {
        PatternInterfaceBlockEntity host = patternHost();
        KeyCounter request = counter(Items.IRON_INGOT, 5L);

        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(new IPatternDetails() {
            @Override
            public AEItemKey getDefinition() {
                return AEItemKey.of(Items.IRON_INGOT);
            }

            @Override
            public IInput[] getInputs() {
                return new IInput[0];
            }

            @Override
            public List<GenericStack> getOutputs() {
                return List.of();
            }
        }, new KeyCounter[]{request}, null)).isFalse();

        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.getLogic().getReturnInv().isEmpty()).isTrue();
    }

    @Test
    void rejects_unknown_patterns_without_taking_requested_material() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        KeyCounter request = counter(Items.IRON_INGOT, 5L);

        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(
                processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2), new KeyCounter[]{request}, null)).isFalse();

        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.getLogic().getReturnInv().isEmpty()).isTrue();
    }

    @Test
    void rejects_unformed_linked_controllers_without_taking_requested_material() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        KeyCounter request = counter(Items.IRON_INGOT, 5L);

        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(
                processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2), new KeyCounter[]{request}, null)).isFalse();

        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.getLogic().getReturnInv().isEmpty()).isTrue();
    }

    @Test
    void rejects_unsupported_request_keys_without_taking_requested_material() {
        PatternInterfaceBlockEntity host = patternHost();
        UnsupportedKey unsupported = new UnsupportedKey();
        KeyCounter request = new KeyCounter();
        request.add(unsupported, 1L);

        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(
                processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2), new KeyCounter[]{request}, null)).isFalse();

        assertThat(request.get(unsupported)).isOne();
        assertThat(host.getLogic().getReturnInv().isEmpty()).isTrue();
    }

    @Test
    void refuses_to_precollect_while_the_normal_controller_is_active() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        RecipeRegistry.registerStatic(recipe("bridge_busy", Items.IRON_NUGGET, 2));
        PatternInterfaceCraftingMachine machine = new PatternInterfaceCraftingMachine(host);

        assertThat(machine.pushPattern(processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2),
                new KeyCounter[]{counter(Items.IRON_INGOT, 5L)}, null)).isTrue();
        assertThat(controller.runtimeSnapshot().crafting().recipeId()).isEqualTo(MMCR.id("bridge_busy"));
        KeyCounter pending = counter(Items.IRON_INGOT, 5L);
        assertThat(machine.pushPattern(processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2),
                new KeyCounter[]{pending}, null)).isFalse();
        assertThat(pending.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
        KeyCounter rejected = counter(Items.IRON_INGOT, 5L);
        assertThat(machine.pushPattern(processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2),
                new KeyCounter[]{rejected}, null)).isFalse();

        assertThat(rejected.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
    }

    @Test
    void rejects_without_payment_until_return_capacity_recovers() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        RecipeRegistry.registerStatic(recipe("bridge_return_capacity", Items.IRON_NUGGET, 2));
        for (int slot = 0; slot < host.getLogic().getReturnInv().size(); slot++) {
            host.getLogic().getReturnInv().setStack(slot,
                    new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 64L));
        }
        KeyCounter request = counter(Items.IRON_INGOT, 5L);

        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(
                processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2), new KeyCounter[]{request}, null)).isFalse();

        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
        for (int slot = 0; slot < host.getLogic().getReturnInv().size(); slot++) {
            assertThat(host.getLogic().getReturnInv().getStack(slot))
                    .isEqualTo(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 64L));
        }
        assertThat(controller.runtimeSnapshot().crafting().recipeId()).isNull();
        host.getLogic().getReturnInv().setStack(0, null);
        assertThat(host.craftingMachine().pushPattern(processingPattern(Items.IRON_INGOT, Items.IRON_NUGGET, 2),
                new KeyCounter[]{request}, null)).isTrue();
        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isZero();
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
        assertThat(controller.runtimeSnapshot().crafting().recipeId()).isEqualTo(MMCR.id("bridge_return_capacity"));
        assertThat(host.getLogic().getReturnInv().getStack(0))
                .isEqualTo(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 3L));
    }

    @Test
    void rolls_back_mixed_request_when_fluid_return_capacity_rejects_after_item_return() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        RecipeRegistry.registerStatic(mixedRecipe("bridge_mixed_return_capacity"));
        for (int slot = 1; slot < host.getLogic().getReturnInv().size(); slot++) {
            host.getLogic().getReturnInv().setStack(slot,
                    new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 64L));
        }
        KeyCounter request = counter(Items.IRON_INGOT, 5L);
        KeyCounter fluidRequest = new KeyCounter();
        fluidRequest.add(AEFluidKey.of(FluidResource.of(Fluids.WATER)), 1_000L);

        assertThat(new PatternInterfaceCraftingMachine(host).pushPattern(
                mixedProcessingPattern(), new KeyCounter[]{request, fluidRequest}, null)).isFalse();

        assertThat(request.get(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(5L);
        assertThat(fluidRequest.get(AEFluidKey.of(FluidResource.of(Fluids.WATER)))).isEqualTo(1_000L);
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
        assertThat(host.getLogic().getReturnInv().getStack(0)).isNull();
        for (int slot = 1; slot < host.getLogic().getReturnInv().size(); slot++) {
            assertThat(host.getLogic().getReturnInv().getStack(slot))
                    .isEqualTo(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 64L));
        }
        assertThat(controller.runtimeSnapshot().crafting().recipeId()).isNull();
    }

    @Test
    void accepts_all_resource_families_as_one_request_and_returns_each_excess_once() {
        PatternInterfaceBlockEntity host = patternHost();
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"), host);
        host.linkControllerAppearance(controller.getBlockPos(), null);
        formForPattern(controller, true);
        var recipe = mixedRecipe("bridge_mixed_shared_request");
        RecipeRegistry.registerStatic(recipe);
        KeyCounter items = counter(Items.IRON_INGOT, 5L);
        KeyCounter fluids = new KeyCounter();
        var water = AEFluidKey.of(FluidResource.of(Fluids.WATER));
        fluids.add(water, 1_000L);
        assertThat(host.craftingMachine().pushPattern(mixedProcessingPattern(), new KeyCounter[]{items, fluids}, null))
                .isTrue();
        assertThat(items.get(AEItemKey.of(Items.IRON_INGOT))).isZero();
        assertThat(fluids.get(water)).isZero();
        assertThat(controller.runtimeSnapshot().crafting().recipeId()).isEqualTo(recipe.id());
        assertThat(host.pendingPatterns().isEmpty()).isTrue();
        assertThat(host.getLogic().getReturnInv().getAvailableStacks().get(AEItemKey.of(Items.IRON_INGOT)))
                .isEqualTo(3L);
        assertThat(host.getLogic().getReturnInv().getAvailableStacks().get(water)).isEqualTo(1_000L);
    }

    private static PatternInterfaceBlockEntity patternHost() {
        return PatternInterfaceKind.INSTANCE.entityFactory().create(PATTERN_PORT, Blocks.IRON_BLOCK.defaultBlockState());
    }

    private static List<MachineControllerBlockEntity> linkedControllers(PatternInterfaceBlockEntity host) {
        MachineControllerBlockEntity first = RuntimeTestFixtures.controllerEntity(MMCR.id("test_cube"), BlockPos.ZERO);
        MachineControllerBlockEntity second = RuntimeTestFixtures.controllerEntity(MMCR.id("test_cube"), new BlockPos(1, 0, 0));
        Level level = LevelStub.create(Map.of(
                first.getBlockPos(), ModBlocks.controllerFor(MMCR.id("test_cube")).get(),
                second.getBlockPos(), ModBlocks.controllerFor(MMCR.id("test_cube")).get(),
                host.getBlockPos(), ModBlocks.BLOCKS.get(host.kind().id()).get()), List.of(first, second, host));
        DynamicMachine machine = new DynamicMachine(MMCR.id("test_cube"), "runtime test", new BlockArray(Map.of()));
        for (MachineControllerBlockEntity controller : List.of(first, second)) {
            controller.setLevel(level);
            controller.setMachine(machine);
            controller.componentRuntime().replaceComponents(List.of(new ProcessingComponent(
                    new MachineComponent(host.kind(), host.ioType()), host, host.getBlockPos(), host.getBlockPos(), (String) null)));
            controller.refreshModuleConnectionState();
        }
        host.setLevel(level);
        return List.of(first, second);
    }

    private static boolean ae2KeyTypesAreInitialized() {
        try {
            return !AEKeyTypes.getAll().isEmpty();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static void initializeAE2KeyTypes() {
        MappedRegistry<AEKeyType> registry = new MappedRegistry<>(AEKeyType.REGISTRY_KEY, Lifecycle.stable());
        AEKeyTypesInternal.setRegistry(registry);
        Registry.register(registry, AEKeyType.items().getId(), AEKeyType.items());
        Registry.register(registry, AEKeyType.fluids().getId(), AEKeyType.fluids());
        registry.freeze();
    }

    @SuppressWarnings("unchecked")
    private static void bindMissingContentItem() throws ReflectiveOperationException {
        MappedRegistry<Item> registry = (MappedRegistry<Item>) BuiltInRegistries.ITEM;
        registry.unfreeze(true);
        try {
            Item item = registry.getValue(AEItems.MISSING_CONTENT.id());
            if (item == null) {
                item = new MissingContentItem(new Item.Properties());
                Registry.register(registry, AEItems.MISSING_CONTENT.id(), item);
            }
            Field itemField = ItemDefinition.class.getDeclaredField("item");
            itemField.setAccessible(true);
            DeferredItem<?> deferredItem = (DeferredItem<?>) itemField.get(AEItems.MISSING_CONTENT);
            Field holder = DeferredHolder.class.getDeclaredField("holder");
            holder.setAccessible(true);
            holder.set(deferredItem, Holder.direct(item));
        } finally {
            registry.freeze();
        }
    }

    @SuppressWarnings("unchecked")
    private static void bindTestPatternInterfaceEntityType() {
        Identifier id = MMCR.id(PatternInterfaceKind.INSTANCE.id());
        MappedRegistry<BlockEntityType<?>> registry = (MappedRegistry<BlockEntityType<?>>) BuiltInRegistries.BLOCK_ENTITY_TYPE;
        registry.unfreeze(true);
        try {
            if (!registry.containsKey(id)) {
                Registry.register(registry, id,
                        new BlockEntityType<>(PatternInterfaceKind.INSTANCE.entityFactory(), Blocks.IRON_BLOCK));
            }
        } finally {
            registry.freeze();
        }
        ModBlockEntities.BES.put(PatternInterfaceKind.INSTANCE.id(),
                DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, id));
    }

    @SuppressWarnings("unchecked")
    private static void bindTestPatternInterfaceBlock() {
        Identifier id = MMCR.id(PatternInterfaceKind.INSTANCE.id());
        MappedRegistry<Block> registry = (MappedRegistry<Block>) BuiltInRegistries.BLOCK;
        registry.unfreeze(true);
        try {
            if (!registry.containsKey(id)) {
                Registry.register(registry, id, new IOPortBlock(PatternInterfaceKind.INSTANCE,
                        () -> ModBlockEntities.BES.get(PatternInterfaceKind.INSTANCE.id()).get(),
                        Blocks.IRON_BLOCK.properties()));
            }
        } finally {
            registry.freeze();
        }
        ModBlocks.BLOCKS.put(PatternInterfaceKind.INSTANCE.id(), DeferredHolder.create(Registries.BLOCK, id));
    }

    private static void formForPattern(MachineControllerBlockEntity controller, boolean linked) {
        controller.setFormed(true);
        controller.componentRuntime().replaceLinkedPortPositions(linked ? Set.of(PATTERN_PORT) : Set.of());
        RuntimeTestFixtures.republish(controller);
    }

    private static MachineRecipe recipe(String path, Item output, int amount) {
        MachineOutput machineOutput = new MachineOutput.ItemOutput(stack(output, amount), 1F);
        return MachineRecipe.fromCanonical(MMCR.id(path), MMCR.id("test_cube"), 20,
                List.of(new ItemRequirement(RecipeModifier.IOType.INPUT, Ingredient.of(Items.IRON_INGOT), 2,
                        ItemStack.EMPTY), OutputRegistry.tryToRequirement(machineOutput, List.of())),
                List.of(machineOutput), List.of(), 0, 1, false, false, false, Set.of());
    }

    private static MachineRecipe mixedRecipe(String path) {
        MachineOutput output = new MachineOutput.ItemOutput(stack(Items.IRON_NUGGET, 2), 1F);
        return MachineRecipe.fromCanonical(MMCR.id(path), MMCR.id("test_cube"), 20,
                List.of(new ItemRequirement(RecipeModifier.IOType.INPUT, Ingredient.of(Items.IRON_INGOT), 2,
                                ItemStack.EMPTY),
                        OutputRegistry.tryToRequirement(output, List.of())),
                List.of(output), List.of(), 0, 1, false, false, false, Set.of());
    }

    private static AEProcessingPattern processingPattern(Item input, Item output, int amount) {
        ItemStack definition = new ItemStack(Items.PAPER);
        AEProcessingPattern.encode(definition,
                List.of(new GenericStack(AEItemKey.of(input), 5L)),
                List.of(new GenericStack(AEItemKey.of(output), amount)));
        return new AEProcessingPattern(AEItemKey.of(definition));
    }

    private static AEProcessingPattern mixedProcessingPattern() {
        ItemStack definition = new ItemStack(Items.PAPER);
        AEProcessingPattern.encode(definition,
                List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 5L),
                        new GenericStack(AEFluidKey.of(FluidResource.of(Fluids.WATER)), 1_000L)),
                List.of(new GenericStack(AEItemKey.of(Items.IRON_NUGGET), 2L)));
        return new AEProcessingPattern(AEItemKey.of(definition));
    }

    private static KeyCounter counter(Item item, long amount) {
        KeyCounter counter = new KeyCounter();
        counter.add(AEItemKey.of(ItemResource.of(item)), amount);
        return counter;
    }

    private static ItemStack stack(Item item, int amount) {
        ItemStack stack = new ItemStack(item, amount);
        stack.set(DataComponents.MAX_STACK_SIZE, 64);
        return stack;
    }

    /** Represents an AE2 key family MMCR does not support. */
    private static final class UnsupportedKey extends AEKey {
        @Override
        public AEKeyType getType() {
            return null;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public void toTag(ValueOutput output) {
        }

        @Override
        public Object getPrimaryKey() {
            return this;
        }

        @Override
        public Identifier getId() {
            return Identifier.fromNamespaceAndPath("test", "unsupported");
        }

        @Override
        public void writeToPacket(RegistryFriendlyByteBuf data) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.empty();
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean hasComponents() {
            return false;
        }
    }
}
