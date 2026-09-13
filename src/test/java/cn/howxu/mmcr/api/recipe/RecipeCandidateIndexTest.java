package cn.howxu.mmcr.api.recipe;

import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.level.LevelModifier;
import cn.howxu.mmcr.api.machine.level.LevelType;
import cn.howxu.mmcr.api.machine.level.MachineLevel;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.EnergyRequirement;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.StatusSeverity;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.internal.multiblock.ModuleConnectionStatus;
import cn.howxu.mmcr.internal.runtime.ComponentRuntime;
import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;
import cn.howxu.mmcr.internal.runtime.CraftingStateSnapshot;
import cn.howxu.mmcr.internal.runtime.FactorySnapshot;
import cn.howxu.mmcr.internal.runtime.StructureSnapshot;
import com.mojang.serialization.Lifecycle;
import java.util.Set;
import net.minecraft.core.Holder;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.test.RecipeTestSupport;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeCandidateIndexTest {

    private static final Identifier MACHINE = Identifier.fromNamespaceAndPath("test", "machine");
    private static final Identifier LEVEL_TYPE = Identifier.fromNamespaceAndPath("test", "recipe_search_level_type");
    private static final Identifier LEVEL = Identifier.fromNamespaceAndPath("test", "recipe_search_level");

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
        bindComponents(Items.IRON_INGOT, Items.GOLD_INGOT, Items.DIAMOND);
        TestBootstrap.registerType(new LevelType(LEVEL_TYPE, Component.literal("Recipe Search Level")));
        TestBootstrap.registerLevel(new MachineLevel(LEVEL, LEVEL_TYPE, 1,
                new BlockPredicate.OfBlockState(Blocks.IRON_BLOCK.defaultBlockState()), ItemStack.EMPTY,
                LevelModifier.IDENTITY));
    }

    @Test
    void candidatesIncludeMatchingExactItemsAndFallbackRecipesInOriginalOrder() {
        MachineRecipe iron = itemRecipe("iron", Ingredient.of(Items.IRON_INGOT));
        MachineRecipe gold = itemRecipe("gold", Ingredient.of(Items.GOLD_INGOT));
        MachineRecipe noItemInput = RecipeTestSupport.create(id("no_item"), MACHINE, 20,
                List.of(), List.of(), List.of(), 0, 1);

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(iron, gold, noItemInput));

        assertThat(index.candidates(List.of(Items.IRON_INGOT))).containsExactly(iron, noItemInput);
        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(noItemInput);
    }

    @Test
    void multiItemIngredientFallsBackRatherThanExcludingAValidRecipe() {
        MachineRecipe alternatives = itemRecipe("alternatives", Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT));

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(alternatives));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(alternatives);
    }

    @Test
    void tagIngredientFallsBackWithoutDereferencingDuringIndexBuild() {
        MachineRecipe tagged = itemRecipe("tagged", Ingredient.of(HolderSet.emptyNamed(BuiltInRegistries.ITEM, ItemTags.SWORDS)));

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(tagged));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(tagged);
    }

    @Test
    void single_member_named_tag_falls_back_instead_of_being_indexed_as_exact() {
        MachineRecipe tagged = itemRecipe("single_member_tag", singleMemberTagIngredient());

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(tagged));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(tagged);
    }

    @Test
    void unbound_named_tag_falls_back_without_throwing() {
        MachineRecipe tagged = itemRecipe("unbound_tag", unboundTagIngredient());

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(tagged));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(tagged);
    }

    @Test
    void custom_ingredient_falls_back_even_when_it_reports_one_item() {
        MachineRecipe custom = itemRecipe("custom_single_item", new Ingredient(new ICustomIngredient() {
            @Override
            public boolean test(ItemStack stack) {
                return stack.is(Items.IRON_INGOT);
            }

            @Override
            public Stream<Holder<Item>> items() {
                return Stream.of(Items.IRON_INGOT.builtInRegistryHolder());
            }

            @Override
            public boolean isSimple() {
                return false;
            }

            @Override
            public IngredientType<?> getType() {
                return null;
            }
        }));

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(custom));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(custom);
    }

    @Test
    void unknown_ingredient_falls_back_when_recipe_also_has_an_exact_item_input() {
        MachineRecipe unknown = RecipeTestSupport.create(id("unknown_with_exact_item"), MACHINE, 20,
                List.of(), List.of(), List.of(), 0, 1, false, List.of(), List.of(
                new ItemRequirement(RecipeModifier.IOType.INPUT, Ingredient.of(Items.IRON_INGOT), 1,
                        ItemStack.EMPTY),
                new ItemRequirement(RecipeModifier.IOType.INPUT, null, 1, ItemStack.EMPTY)), false);

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(unknown));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(unknown);
    }

    @Test
    void non_item_inputs_fall_back_when_recipe_also_has_an_exact_item_input() {
        MachineRecipe mixed = RecipeTestSupport.create(id("mixed_exact_item_and_energy"), MACHINE, 20,
                List.of(), List.of(), List.of(), 0, 1, false, List.of(), List.of(
                new ItemRequirement(RecipeModifier.IOType.INPUT, Ingredient.of(Items.IRON_INGOT), 1,
                        ItemStack.EMPTY),
                new EnergyRequirement(RecipeModifier.IOType.INPUT, 1)), false);

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(mixed));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(mixed);
    }

    @Test
    void capability_tagged_inputs_fall_back_when_recipe_also_has_an_exact_item_input() {
        MachineRecipe tagged = RecipeTestSupport.create(id("tagged_exact_item"), MACHINE, 20,
                List.of(), List.of(), List.of(), 0, 1, false, List.of(), List.of(
                new ItemRequirement(RecipeModifier.IOType.INPUT, Ingredient.of(Items.IRON_INGOT), 1,
                        ItemStack.EMPTY, List.of("north_buses"))), false);

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(tagged));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(tagged);
    }

    @Test
    void tagged_non_item_requirements_also_fall_back_when_recipe_has_an_exact_item_input() {
        MachineRecipe tagged = RecipeTestSupport.create(id("tagged_energy_with_exact_item"), MACHINE, 20,
                List.of(), List.of(), List.of(), 0, 1, false, List.of(), List.of(
                new ItemRequirement(RecipeModifier.IOType.INPUT, Ingredient.of(Items.IRON_INGOT), 1,
                        ItemStack.EMPTY),
                new EnergyRequirement(RecipeModifier.IOType.OUTPUT, 1, List.of("energy_hatches"))), false);

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(tagged));

        assertThat(index.candidates(List.of(Items.DIAMOND))).containsExactly(tagged);
    }

    @Test
    void unknown_input_types_do_not_filter_exact_item_candidates() {
        MachineRecipe exact = itemRecipe("unknown_input_types", Ingredient.of(Items.IRON_INGOT));

        RecipeCandidateIndex index = RecipeCandidateIndex.build(MACHINE, List.of(exact));

        assertThat(index.candidates(null)).containsExactly(exact);
    }

    @Test
    void mixed_pool_recipes_cannot_share_a_candidate_index() {
        MachineRecipe firstPoolRecipe = itemRecipe("first_pool", Ingredient.of(Items.IRON_INGOT));
        MachineRecipe secondPoolRecipe = RecipeTestSupport.create(id("second_pool"),
                Identifier.fromNamespaceAndPath("test", "other_pool"), 20,
                List.of(), List.of(), List.of(), 0, 1);

        assertThatThrownBy(() -> RecipeCandidateIndex.build(MACHINE, List.of(firstPoolRecipe, secondPoolRecipe)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recipe pool");
    }

    @Test
    void preordered_candidates_preserve_supplied_order_and_lock_singleton() {
        MachineRecipe highPriority = RecipeTestSupport.create(id("high_priority"), MACHINE, 20,
                List.of(), List.of(), List.of(), 0, 1);
        MachineRecipe moreInputs = RecipeTestSupport.create(id("more_inputs"), MACHINE, 20,
                List.of(), List.of(), List.of(), 1, 1);
        MachineRecipe idTieBreaker = RecipeTestSupport.create(id("id_tie_breaker"), MACHINE, 20,
                List.of(), List.of(), List.of(), 2, 1);
        List<MachineRecipe> supplied = List.of(idTieBreaker, highPriority, moreInputs);
        RecipeSearchResult existing = new RecipeSearchTask(emptySnapshot(), MACHINE, 0L, 1,
                supplied, null, List.of()).compute();

        RecipeSearchResult result = new RecipeSearchTask(emptySnapshot(), MACHINE, 0L, 1,
                supplied, null, List.of(), List.of()).compute();
        RecipeSearchResult locked = new RecipeSearchTask(emptySnapshot(), MACHINE, 0L, 1,
                supplied, moreInputs.id(), List.of(), List.of()).compute();

        assertThat(existing.recipe()).isEqualTo(highPriority);
        assertThat(result.recipe()).isEqualTo(idTieBreaker);
        assertThat(locked.recipe()).isEqualTo(moreInputs);
    }

    @Test
    void search_failure_priority_prefers_missing_inputs_then_energy_then_levels() {
        ExecutionStatus missingInput = failure("insufficient_resource");
        ExecutionStatus missingEnergy = failure("insufficient_energy");
        ExecutionStatus missingOutput = failure("no_output_capacity");

        assertThat(RecipeSearchTask.failurePriority(missingInput))
                .isLessThan(RecipeSearchTask.failurePriority(missingEnergy));
        assertThat(RecipeSearchTask.failurePriority(missingInput))
                .isLessThan(RecipeSearchTask.LEVEL_FAILURE_PRIORITY);
        assertThat(RecipeSearchTask.LEVEL_FAILURE_PRIORITY)
                .isLessThan(RecipeSearchTask.failurePriority(missingOutput));
    }

    @Test
    void search_prefers_missing_input_over_energy_and_level_requirements() {
        MachineRecipe levelLimited = RecipeTestSupport.create(id("level_limited"), MACHINE, 20,
                List.of(), List.of(), List.of(), 0, 1, false, List.of(), List.of(), false,
                List.of(new LevelRequirement(LEVEL_TYPE, LEVEL)), false, Set.of());
        MachineRecipe energyLimited = RecipeTestSupport.create(id("energy_limited"), MACHINE, 20,
                List.of(new EnergyRequirement(RecipeModifier.IOType.INPUT, 1)), List.of(), List.of(), 0, 1);
        MachineRecipe inputLimited = itemRecipe("input_limited", Ingredient.of(Items.IRON_INGOT));

        RecipeSearchResult result = new RecipeSearchTask(emptySnapshot(), MACHINE, 0L, 1L,
                List.of(levelLimited, energyLimited, inputLimited), null, List.of(), List.of()).compute();

        assertThat(result.failureUnloc()).isEqualTo("gui.mmcr.controller.failure.missing_input");
    }

    private static ControllerRuntimeSnapshot emptySnapshot() {
        return new ControllerRuntimeSnapshot(StructureSnapshot.empty(), 0L, 0L, 0L,
                Map.of(), Map.of(), Set.of(),
                ModuleConnectionStatus.notRequired(), 0,
                new ComponentRuntime.CapabilityAggregate(0L, 0L, null, null),
                CraftingStateSnapshot.empty(0L, 0L, 0L),
                FactorySnapshot.empty(), List.of(), List.of(), List.of(),
                "", "", 0, false, false, 0, 0, 1, Map.of());
    }

    private static MachineRecipe itemRecipe(String path, Ingredient ingredient) {
        return RecipeTestSupport.create(id(path), MACHINE, 20, List.of(), List.of(), List.of(), 0, 1, false, List.of(),
                List.of(new ItemRequirement(RecipeModifier.IOType.INPUT, ingredient, 1, ItemStack.EMPTY)), false);
    }

    private static Ingredient singleMemberTagIngredient() {
        MappedRegistry<Item> registry = new MappedRegistry<>(
                ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("mmcr_test", "tag_items")),
                Lifecycle.stable(), true);
        Holder.Reference<Item> holder = registry.createIntrusiveHolder(Items.IRON_INGOT);
        registry.register(ResourceKey.create(registry.key(), id("tagged_item")), Items.IRON_INGOT, RegistrationInfo.BUILT_IN);
        TagKey<Item> tag = TagKey.create(registry.key(), id("single_member"));
        registry.bindTags(Map.of(tag, List.of(holder)));
        registry.freeze();
        return Ingredient.of(registry.get(tag).orElseThrow());
    }

    private static Ingredient unboundTagIngredient() {
        TagKey<Item> tag = TagKey.create(Registries.ITEM, id("unbound"));
        return Ingredient.of(HolderSet.emptyNamed(BuiltInRegistries.ITEM, tag));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("test", path);
    }

    private static ExecutionStatus failure(String reason) {
        return new ExecutionStatus(MMCR.id("recipe_search_test"), StatusSeverity.BLOCKED,
                MMCR.id("recipe_search_test"), Map.of("reason", reason));
    }

    private static void bindComponents(Item... items) {
        for (Item item : items) item.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
    }
}
