package cn.howxu.mmcr.datagen;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.registry.ModItems;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.ModLoadedCondition;
import net.neoforged.neoforge.common.Tags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the AppFlux conditional recipe path emits the expected
 * {@code ae2} + {@code appflux} conditions when the optional accessor item
 * is available in the registry.
 *
 * @author howxu <dev@howxu.cn>
 */
class AppliedFluxRecipeProviderTest {
    private static final String INPUT_ID = "appflux_me_flux_input_interface";
    private static final String OUTPUT_ID = "appflux_me_flux_output_interface";
    private static final Identifier ACCESSOR_ID =
            Identifier.fromNamespaceAndPath("appflux", "flux_accessor");

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void appliedFluxInterfaceRecipesAreConditionedOnAppfluxAndAe2() throws Exception {
        if (!ModItems.ITEMS.containsKey(INPUT_ID) || !ModItems.ITEMS.containsKey(OUTPUT_ID)) {
            // Without the AppFlux port kinds loaded, the conditional branch must stay out.
            return;
        }
        Item stubAccessor = installAccessorStub();
        installRecipeTestTags();

        Map<Identifier, List<ICondition>> conditions = new LinkedHashMap<>();
        RecipeOutput output = new RecipeOutput() {
            @Override
            public void accept(ResourceKey<Recipe<?>> id, Recipe<?> recipe, AdvancementHolder advancement,
                               ICondition... recipeConditions) {
                conditions.put(id.identifier(), List.of(recipeConditions));
            }

            @Override
            public Advancement.Builder advancement() {
                return Advancement.Builder.recipeAdvancement();
            }

            @Override
            public void includeRootAdvancement() {
            }
        };
        HolderLookup.Provider lookup = HolderLookup.Provider.create(Stream.of(BuiltInRegistries.ITEM));
        ModRecipeProvider provider = new ModRecipeProvider(lookup, output);
        Method buildRecipes = ModRecipeProvider.class.getDeclaredMethod("buildRecipes");
        buildRecipes.setAccessible(true);
        buildRecipes.invoke(provider);

        Identifier inputRecipe = MMCR.id(INPUT_ID);
        Identifier outputRecipe = MMCR.id(OUTPUT_ID);
        assertThat(conditions).containsKey(inputRecipe);
        assertThat(conditions).containsKey(outputRecipe);
        assertThat(conditions.get(inputRecipe))
                .anySatisfy(condition -> assertModLoaded(condition, "ae2"))
                .anySatisfy(condition -> assertModLoaded(condition, "appflux"));
        assertThat(conditions.get(outputRecipe))
                .anySatisfy(condition -> assertModLoaded(condition, "ae2"))
                .anySatisfy(condition -> assertModLoaded(condition, "appflux"));

        assertThat(stubAccessor).isNotNull();
        // The accessor stub is only used while AppFlux items are registered; the assertions above
        // are the actual contract: both recipe outputs are guarded by the AppFlux condition.
    }

    private static void assertModLoaded(ICondition condition, String expectedModId) {
        if (condition instanceof ModLoadedCondition loaded) {
            assertThat(loaded.modid()).isEqualTo(expectedModId);
            return;
        }
        throw new AssertionError("Expected ModLoadedCondition(" + expectedModId + ") but was " + condition);
    }

    private static Item installAccessorStub() {
        MappedRegistry<Item> registry = (MappedRegistry<Item>) BuiltInRegistries.ITEM;
        registry.unfreeze(true);
        try {
            if (!registry.containsKey(ACCESSOR_ID)) {
                Item stub = new Item(new Item.Properties().setId(
                        ResourceKey.create(Registries.ITEM, ACCESSOR_ID)));
                Registry.register(registry, ACCESSOR_ID, stub);
                return stub;
            }
            return registry.getValue(ACCESSOR_ID);
        } finally {
            registry.freeze();
        }
    }

    private static void installRecipeTestTags() {
        Map<TagKey<Item>, List<Holder<Item>>> tags = new LinkedHashMap<>();
        tags.put(Tags.Items.INGOTS_COPPER, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.COPPER_INGOT)));
        tags.put(Tags.Items.INGOTS_IRON, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.IRON_INGOT)));
        tags.put(Tags.Items.DUSTS_REDSTONE, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.REDSTONE)));
        tags.put(Tags.Items.DUSTS_GLOWSTONE, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.GLOWSTONE_DUST)));
        tags.put(Tags.Items.STORAGE_BLOCKS_REDSTONE,
                List.of(BuiltInRegistries.ITEM.wrapAsHolder(Blocks.REDSTONE_BLOCK.asItem())));
        tags.put(Tags.Items.GEMS_DIAMOND, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.DIAMOND)));
        tags.put(Tags.Items.CHESTS, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.CHEST)));
        tags.put(Tags.Items.GLASS_PANES, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.GLASS_PANE)));
        tags.put(Tags.Items.GEMS_LAPIS, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.LAPIS_LAZULI)));
        tags.put(Tags.Items.GEMS_AMETHYST, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.AMETHYST_SHARD)));
        tags.put(Tags.Items.INGOTS_GOLD, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.GOLD_INGOT)));
        tags.put(Tags.Items.INGOTS_NETHERITE, List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.NETHERITE_INGOT)));
        tags.put(ItemTags.create(Identifier.withDefaultNamespace("bookshelf_books")),
                List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.BOOK)));
        BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, tags)).apply();
    }
}