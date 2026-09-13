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

    boolean isEmpty() {
        return structures.isEmpty() && recipes.isEmpty();
    }

    RuntimeContentCoordinator.CommitResult commit() {
        Map<Identifier, MachineStructureDefinition> mergedStructures = new LinkedHashMap<>(
                MachineStructureRegistry.dynamicSnapshot());
        removePublishedStructures(mergedStructures);
        mergedStructures.putAll(structures);
        Map<Identifier, MachineRecipe> mergedRecipes = new LinkedHashMap<>(RecipeRegistry.dynamicSnapshot());
        removePublishedRecipes(mergedRecipes);
        List<MachineRecipeJson.RecipeJsonException> transactionErrors = new ArrayList<>();
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
        }
        RuntimeContentCoordinator.CommitResult committed =
                RuntimeContentCoordinator.commitDynamicAndSnapshot(mergedStructures, mergedRecipes);
        if (!transactionErrors.isEmpty()) {
            DynamicContentReloadService.ReloadResult result = committed.result();
            List<MachineRecipeJson.RecipeJsonException> errors = new ArrayList<>(result.errors());
            errors.addAll(transactionErrors);
            committed = new RuntimeContentCoordinator.CommitResult(
                    new DynamicContentReloadService.ReloadResult(result.addedStructures(), result.updatedStructures(),
                            result.removedStructures(), result.addedRecipes(), result.updatedRecipes(),
                            result.removedRecipes(), errors), committed.snapshot());
        }
        publishedStructures = Map.copyOf(structures);
        Map<Identifier, MachineRecipe> validRecipes = new LinkedHashMap<>(recipes);
        committed.result().errors().forEach(error -> validRecipes.remove(error.recipeId()));
        publishedRecipes = Map.copyOf(validRecipes);
        return committed;
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
