package cn.howxu.mmcr.compat.kubejs;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.MachineStructureDefinition;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.MachineRecipeJson;
import cn.howxu.mmcr.api.machine.MachineStructureRegistry;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.internal.reload.DynamicContentReloadService;
import cn.howxu.mmcr.internal.registration.RuntimeContentCoordinator;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects server-script content until it can replace both dynamic snapshots together.
 *
 * @author howxu <dev@howxu.cn>
 */
final class KubeJSContentReloadTransaction {
    private static final ThreadLocal<KubeJSContentReloadTransaction> ACTIVE = new ThreadLocal<>();
    private static Map<Identifier, MachineStructureDefinition> publishedStructures = Map.of();
    private static Map<Identifier, MachineRecipe> publishedRecipes = Map.of();

    private final Map<Identifier, MachineStructureDefinition> structures = new LinkedHashMap<>();
    private final Map<Identifier, MachineRecipe> recipes = new LinkedHashMap<>();

    static KubeJSContentReloadTransaction active() {
        return ACTIVE.get();
    }

    static boolean ownsRecipe(Identifier id) {
        KubeJSContentReloadTransaction active = ACTIVE.get();
        return active != null && active.recipes.containsKey(id);
    }

    static boolean ownsRecipe(MachineRecipe recipe) {
        KubeJSContentReloadTransaction active = ACTIVE.get();
        if (active == null || recipe == null) return false;
        return active.recipes.values().stream()
                .anyMatch(owned -> owned.equals(recipe.withId(owned.id())));
    }

    static void activate(KubeJSContentReloadTransaction transaction) {
        ACTIVE.set(transaction);
    }

    static void deactivate() {
        ACTIVE.remove();
    }

    static void clearPublishedForTesting() {
        publishedStructures = Map.of();
        publishedRecipes = Map.of();
    }

    void registerStructure(MachineStructureDefinition structure) {
        Identifier id = structure.machineId();
        if (structures.putIfAbsent(id, structure) != null) {
            throw new IllegalStateException("Dynamic structure already registered: " + id);
        }
    }

    void registerRecipe(MachineRecipe recipe) {
        Identifier id = recipe.id();
        if (recipes.putIfAbsent(id, recipe) != null) {
            throw new IllegalStateException("Dynamic recipe already registered: " + id);
        }
    }

    boolean hasPublishableContent() {
        PreparedContent content = prepareContent();
        return RuntimeContentCoordinator.hasPublishableDynamicContent(content.structures(), content.recipes(),
                structures.keySet(), content.transactionRecipes().keySet());
    }

    RuntimeContentCoordinator.CommitResult commit() {
        PreparedContent content = prepareContent();
        RuntimeContentCoordinator.CommitResult committed = RuntimeContentCoordinator.commitDynamicAndSnapshot(
                content.structures(), content.recipes());
        if (!content.errors().isEmpty()) {
            DynamicContentReloadService.ReloadResult result = committed.result();
            List<MachineRecipeJson.RecipeJsonException> errors = new ArrayList<>(result.errors());
            errors.addAll(content.errors());
            committed = new RuntimeContentCoordinator.CommitResult(
                    new DynamicContentReloadService.ReloadResult(result.addedStructures(), result.updatedStructures(),
                            result.removedStructures(), result.addedRecipes(), result.updatedRecipes(),
                            result.removedRecipes(), errors), committed.snapshot());
        }
        Map<Identifier, MachineRecipe> validRecipes = new LinkedHashMap<>(content.transactionRecipes());
        committed.result().errors().forEach(error -> validRecipes.remove(error.recipeId()));
        publishedStructures = Map.copyOf(structures);
        publishedRecipes = Map.copyOf(validRecipes);
        return committed;
    }

    private PreparedContent prepareContent() {
        Map<Identifier, MachineStructureDefinition> previousStructures = MachineStructureRegistry.dynamicSnapshot();
        Map<Identifier, MachineStructureDefinition> mergedStructures = new LinkedHashMap<>(previousStructures);
        removePublishedStructures(mergedStructures);
        mergedStructures.putAll(structures);
        Map<Identifier, MachineRecipe> previousRecipes = RecipeRegistry.dynamicSnapshot();
        Map<Identifier, MachineRecipe> mergedRecipes = new LinkedHashMap<>(previousRecipes);
        removePublishedRecipes(mergedRecipes);
        List<MachineRecipeJson.RecipeJsonException> transactionErrors = new ArrayList<>();
        Map<Identifier, MachineRecipe> transactionRecipes = new LinkedHashMap<>();
        for (Map.Entry<Identifier, MachineRecipe> entry : recipes.entrySet()) {
            Identifier id = entry.getKey();
            MachineRecipe recipe = entry.getValue();
            MachineRecipe existing = mergedRecipes.get(id);
            if (existing != null && !existing.recipePoolId().equals(recipe.recipePoolId())) {
                transactionErrors.add(new MachineRecipeJson.RecipeJsonException(id, "recipe_pool",
                        "recipe already belongs to pool " + existing.recipePoolId(), null));
                MMCR.LOG.warn("Skipping KubeJS recipe {} from pool {}: recipe already belongs to pool {}",
                        id, recipe.recipePoolId(), existing.recipePoolId());
                continue;
            }
            mergedRecipes.put(id, recipe);
            transactionRecipes.put(id, recipe);
        }
        return new PreparedContent(mergedStructures, mergedRecipes, transactionRecipes, transactionErrors);
    }

    private record PreparedContent(Map<Identifier, MachineStructureDefinition> structures,
                                   Map<Identifier, MachineRecipe> recipes,
                                   Map<Identifier, MachineRecipe> transactionRecipes,
                                   List<MachineRecipeJson.RecipeJsonException> errors) {
    }

    private static void removePublishedStructures(Map<Identifier, MachineStructureDefinition> mergedStructures) {
        publishedStructures.forEach((id, structure) -> mergedStructures.computeIfPresent(id,
                (ignored, current) -> current.equals(structure) ? null : current));
    }

    private static void removePublishedRecipes(Map<Identifier, MachineRecipe> mergedRecipes) {
        publishedRecipes.forEach((id, recipe) -> mergedRecipes.computeIfPresent(id,
                (ignored, current) -> current.equals(recipe) ? null : current));
    }

}
