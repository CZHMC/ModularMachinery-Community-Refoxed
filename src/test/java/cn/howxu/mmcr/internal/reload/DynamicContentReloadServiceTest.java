package cn.howxu.mmcr.internal.reload;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.MachineDefinitions;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.machine.MachineRegistration;
import cn.howxu.mmcr.api.machine.MachineStructureDefinition;
import cn.howxu.mmcr.api.machine.MachineStructureRequirements;
import cn.howxu.mmcr.api.machine.MachineStructureRegistry;
import cn.howxu.mmcr.api.machine.PortRequirementSpec;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.OutputType;
import cn.howxu.mmcr.api.recipe.CustomOutput;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.modifier.SingleBlockModifierReplacement;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.test.RecipeTestSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import cn.howxu.mmcr.api.machine.BlockArrayCache;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import net.minecraft.core.Direction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicContentReloadServiceTest {

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @BeforeEach
    void restoreStartupDefinitions() {
        TestBootstrap.restoreMachineDefinitions();
        MachineRegistry.clearForTesting();
        MachineStructureRegistry.clearForTesting();
        RecipeRegistry.clearForTesting();
    }

    @AfterEach
    void cleanup() {
        MachineDefinitions.clearForTesting();
        MachineRegistry.clearForTesting();
        MachineStructureRegistry.clearForTesting();
        RecipeRegistry.clearForTesting();
    }

    @Test
    void producerFailureRetainsPreviousDynamicSnapshot() {
        register("mmcr:test_cube");
        register("mmcr:controller_tick");
        DynamicContentReloadService.reload(candidate -> {
            candidate.registerStructure(structure("mmcr:test_cube"));
            candidate.registerRecipe(recipe("mmcr:old_recipe", "mmcr:test_cube"));
        });

        assertThatThrownBy(() -> DynamicContentReloadService.reload(candidate -> {
            candidate.registerStructure(structure("mmcr:controller_tick"));
            throw new IllegalStateException("script failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(MachineRegistry.getMachine(Identifier.parse("mmcr:test_cube"))).isNotNull();
        assertThat(RecipeRegistry.getRecipe(Identifier.parse("mmcr:old_recipe"))).isNotNull();
        assertThat(MachineRegistry.getMachine(Identifier.parse("mmcr:controller_tick"))).isNull();
    }

    @Test
    void successfulReloadReportsRemovedStructuresAndDropsTheirRecipes() {
        register("mmcr:test_cube");
        register("mmcr:controller_tick");
        DynamicContentReloadService.reload(candidate -> {
            candidate.registerStructure(structure("mmcr:test_cube"));
            candidate.registerStructure(structure("mmcr:controller_tick"));
            candidate.registerRecipe(recipe("mmcr:old_recipe", "mmcr:test_cube"));
        });

        var result = DynamicContentReloadService.reload(candidate ->
                candidate.registerStructure(structure("mmcr:controller_tick")));

        assertThat(result.removedStructures()).containsExactly(Identifier.parse("mmcr:test_cube"));
        assertThat(MachineRegistry.getCompiled(Identifier.parse("mmcr:test_cube"))).isNull();
        assertThat(RecipeRegistry.getRecipe(Identifier.parse("mmcr:old_recipe"))).isNull();
    }

    @Test
    void compilationFailureRetainsPreviousRuntimeSnapshotAndCache() {
        register("mmcr:test_cube");
        register("mmcr:controller_tick");
        var oldStructure = structure("mmcr:test_cube");
        DynamicContentReloadService.reload(candidate -> candidate.registerStructure(oldStructure));
        var oldMachine = MachineRegistry.getMachine(Identifier.parse("mmcr:test_cube"));
        var oldCompiled = MachineRegistry.getCompiled(oldMachine.registryName());
        var oldRotated = BlockArrayCache.get(oldMachine.pattern(), Direction.NORTH);

        assertThatThrownBy(() -> DynamicContentReloadService.reload(candidate ->
                candidate.registerStructure(failingStructure("mmcr:controller_tick"))))
                .isInstanceOf(RuntimeException.class);

        assertThat(MachineRegistry.getMachine(oldMachine.registryName())).isNotNull();
        assertThat(MachineRegistry.getCompiled(oldMachine.registryName())).isSameAs(oldCompiled);
        assertThat(BlockArrayCache.get(oldMachine.pattern(), Direction.NORTH))
                .isSameAs(oldRotated);
        assertThat(MachineRegistry.getMachine(Identifier.parse("mmcr:controller_tick"))).isNull();
    }

    @Test
    void candidateRecipeCanReferenceStaticMachine() {
        var staticMachine = new DynamicMachine(Identifier.parse("mmcr:static"), "mmcr:static", new BlockArray(Map.of()));
        MachineRegistry.register(staticMachine);
        DynamicContentReloadService.reload(candidate ->
                candidate.registerRecipe(recipe("mmcr:static_recipe", "mmcr:static")));
        assertThat(RecipeRegistry.getRecipe(Identifier.parse("mmcr:static_recipe"))).isNotNull();
    }

    @Test
    void candidateRecipeCanReferenceStartupMachineDefinitionWithoutDynamicStructure() {
        Identifier machineId = Identifier.parse("mmcr:startup_definition_only");
        register(machineId.toString());
        MachineStructureRegistry.replaceStartup(Map.of(machineId, structure(machineId.toString())));

        DynamicContentReloadService.reload(candidate ->
                candidate.registerRecipe(recipe("mmcr:startup_definition_recipe", machineId.toString())));

        assertThat(RecipeRegistry.getRecipe(Identifier.parse("mmcr:startup_definition_recipe"))).isNotNull();
    }

    @Test
    void orphan_dynamic_recipe_is_reported_while_valid_recipe_continues_publishing() {
        Identifier machineId = Identifier.parse("mmcr:dynamic_pool_validation_machine");
        Identifier validId = Identifier.parse("mmcr:dynamic_pool_validation_valid");
        Identifier orphanId = Identifier.parse("mmcr:dynamic_pool_validation_orphan");
        register(machineId.toString());

        var result = DynamicContentReloadService.reload(candidate -> {
            candidate.registerStructure(structure(machineId.toString()));
            candidate.registerRecipe(recipe(validId, machineId));
            candidate.registerRecipe(recipe(orphanId, MMCR.id("missing_dynamic_recipe_pool")));
        });

        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.recipeId()).isEqualTo(orphanId);
            assertThat(error.path()).isEqualTo("recipe_pool");
            assertThat(error.getMessage()).contains("missing_dynamic_recipe_pool");
        });
        assertThat(RecipeRegistry.dynamicSnapshot()).containsEntry(validId, recipe(validId, machineId))
                .doesNotContainKey(orphanId);
    }

    @Test
    void invalid_dynamic_recipe_is_reported_while_valid_recipe_continues_publishing() {
        Identifier machineId = Identifier.parse("mmcr:dynamic_recipe_validation_machine");
        Identifier validId = Identifier.parse("mmcr:dynamic_recipe_validation_valid");
        Identifier invalidId = Identifier.parse("mmcr:dynamic_recipe_validation_invalid");
        register(machineId.toString());
        MachineRecipe invalid;
        try (var scope = OutputRegistry.openTestScope()) {
            OutputRegistry.register(INVALID_OUTPUT_TYPE);
            invalid = RecipeTestSupport.create(invalidId, machineId, 1, List.of(),
                    List.of(new InvalidOutput(1, 1F)));
        }

        var result = DynamicContentReloadService.reload(candidate -> {
            candidate.registerStructure(structure(machineId.toString()));
            candidate.registerRecipe(recipe(validId, machineId));
            candidate.registerRecipe(invalid);
        });

        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.recipeId()).isEqualTo(invalidId);
            assertThat(error.path()).isEqualTo("outputs[0]");
        });
        assertThat(RecipeRegistry.dynamicSnapshot()).containsKey(validId).doesNotContainKey(invalidId);
    }

    @Test
    void reloadWithSnapshotReturnsTheCommittedEffectiveContent() {
        String machineId = "mmcr:test_machine_name";

        var commit = DynamicContentReloadService.reloadWithSnapshot(candidate ->
                candidate.registerStructure(structure(machineId)));

        assertThat(commit.result()).isNotNull();
        assertThat(commit.snapshot().structures()).containsKey(Identifier.parse(machineId));
        assertThat(commit.snapshot().contentVersion()).isGreaterThan(0L);
        assertThat(commit.snapshot().structures()).isEqualTo(MachineStructureRegistry.effectiveSnapshot());
        assertThat(commit.snapshot().recipes()).isEqualTo(RecipeRegistry.effectiveSnapshot());
    }

    private static void register(String id) {
        Identifier identifier = Identifier.parse(id);
        if (MachineDefinitions.getRegistration(identifier) == null) {
            MachineDefinitions.beginRegistryPhase();
            MachineDefinitions.register(MachineRegistration.builder(identifier).build());
        }
    }

    private static MachineRecipe recipe(String id, String machineId) {
        return RecipeTestSupport.create(Identifier.parse(id), Identifier.parse(machineId), 1, List.of(), List.of());
    }

    private static MachineRecipe recipe(Identifier id, Identifier recipePoolId) {
        return RecipeTestSupport.create(id, recipePoolId, 1, List.of(), List.of());
    }

    private static MachineStructureDefinition structure(String id) {
        Identifier identifier = Identifier.parse(id);
        return new MachineStructureDefinition(identifier, new BlockArray(Map.of()), PortRequirementSpec.none(), List.of(),
                MachineStructureRequirements.EMPTY);
    }

    private static MachineStructureDefinition failingStructure(String id) {
        Identifier identifier = Identifier.parse(id);
        BlockPos outsidePattern = new BlockPos(1, 0, 0);
        var replacement = new SingleBlockModifierReplacement("invalid", new BlockPredicate.OfBlock(Blocks.GOLD_BLOCK), List.of(), ItemStack.EMPTY);
        return new MachineStructureDefinition(identifier, new BlockArray(Map.of()), PortRequirementSpec.none(), List.of(),
                MachineStructureRequirements.builder().modifier('X', replacement).build());
    }

    private static final Identifier INVALID_OUTPUT_ID = Identifier.parse("mmcr_test:dynamic_invalid_output");
    private static final OutputType<InvalidOutput> INVALID_OUTPUT_TYPE = new OutputType.Definition<>(
            INVALID_OUTPUT_ID, com.mojang.serialization.MapCodec.unit(() -> new InvalidOutput(1, 1F)),
            (output, chance) -> new InvalidOutput(output.value(), chance),
            (output, modifiers) -> output,
            output -> new InvalidOutput(output.value(), output.chance()));

    private record InvalidOutput(int value, float chance) implements CustomOutput {
        private InvalidOutput {
            chance = MachineOutput.clampChance(chance);
        }

        @Override
        public OutputType<InvalidOutput> outputType() {
            return INVALID_OUTPUT_TYPE;
        }
    }
}
