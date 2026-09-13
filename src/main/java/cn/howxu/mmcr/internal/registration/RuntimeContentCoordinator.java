package cn.howxu.mmcr.internal.registration;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.MachineDefinitions;
import cn.howxu.mmcr.api.machine.MachineRegistration;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.machine.MachineRoleValidator;
import cn.howxu.mmcr.api.machine.MachineStructureDefinition;
import cn.howxu.mmcr.api.machine.MachineStructureRegistry;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.MachineRecipeJson;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.modifier.ModifierRegistry;
import cn.howxu.mmcr.internal.reload.DynamicContentReloadService;
import cn.howxu.mmcr.internal.network.ControllerSpecSync;
import cn.howxu.mmcr.internal.sync.RuntimeContentSnapshot;
import cn.howxu.mmcr.internal.sync.RuntimeContentVersion;
import java.util.ArrayList;
import java.util.Collection;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Commits runtime content layers as validated reload transactions.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class RuntimeContentCoordinator {
    private RuntimeContentCoordinator() {
    }

    public static DynamicContentReloadService.ReloadResult commitDynamic(
            Map<Identifier, MachineStructureDefinition> structures,
            Map<Identifier, MachineRecipe> recipes) {
        synchronized (RuntimeContentVersion.lock()) {
            return commitDynamicLocked(structures, recipes).result();
        }
    }

    public static CommitResult commitCurrentDynamicAndSnapshot() {
        synchronized (RuntimeContentVersion.lock()) {
            return commitDynamicLocked(MachineStructureRegistry.dynamicSnapshot(), RecipeRegistry.dynamicSnapshot());
        }
    }

    private static CommitResult commitDynamicLocked(
            Map<Identifier, MachineStructureDefinition> structures,
            Map<Identifier, MachineRecipe> recipes) {
        Map<Identifier, MachineStructureDefinition> oldStructures = MachineStructureRegistry.dynamicSnapshot();
        Map<Identifier, MachineRecipe> oldRecipes = RecipeRegistry.dynamicSnapshot();
        Map<Identifier, MachineStructureDefinition> structureReplacement = Map.copyOf(new LinkedHashMap<>(structures));
        Map<Identifier, MachineRecipe> candidateRecipes = Map.copyOf(new LinkedHashMap<>(recipes));
        validate(structureReplacement, candidateRecipes);
        List<MachineRecipeJson.RecipeJsonException> recipeErrors = new ArrayList<>();
        Map<Identifier, MachineRecipe> validRecipes = new LinkedHashMap<>();
        for (Map.Entry<Identifier, MachineRecipe> entry : candidateRecipes.entrySet()) {
            MachineRecipe recipe = entry.getValue();
            if (!recipePoolAvailable(recipe.recipePoolId(), structureReplacement)) {
                recipeErrors.add(new MachineRecipeJson.RecipeJsonException(entry.getKey(), "recipe_pool",
                        "unknown recipe pool " + recipe.recipePoolId(), null));
                MMCR.LOG.warn("Skipping dynamic recipe {}: unknown recipe pool {} at recipe_pool",
                        entry.getKey(), recipe.recipePoolId());
                continue;
            }
            try {
                RecipeRegistry.validateDataPackCandidate(Map.of(entry.getKey(), recipe));
                validRecipes.put(entry.getKey(), recipe);
            } catch (MachineRecipeJson.RecipeJsonException exception) {
                recipeErrors.add(exception);
                MMCR.LOG.warn("Skipping invalid dynamic recipe {}", entry.getKey(), exception);
            } catch (RuntimeException exception) {
                MachineRecipeJson.RecipeJsonException error = new MachineRecipeJson.RecipeJsonException(
                        entry.getKey(), "$",
                        exception.getMessage() == null ? "candidate validation failed" : exception.getMessage(),
                        exception);
                recipeErrors.add(error);
                MMCR.LOG.warn("Skipping invalid dynamic recipe {}", entry.getKey(), exception);
            }
        }
        Map<Identifier, MachineRecipe> recipeReplacement = Map.copyOf(validRecipes);

        try {
            MachineStructureRegistry.replaceDynamic(structureReplacement);
            RecipeRegistry.replaceDynamic(recipeReplacement);
            DynamicContentReloadService.ReloadResult result = DynamicContentReloadService.ReloadResult.fromSnapshots(
                    oldStructures, structureReplacement, oldRecipes, recipeReplacement, recipeErrors);
            return new CommitResult(result, snapshotLocked());
        } catch (RuntimeException | Error failure) {
            try {
                MachineStructureRegistry.replaceDynamic(oldStructures);
                RecipeRegistry.replaceDynamic(oldRecipes);
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    public static CommitResult commitDynamicAndSnapshot(
            Map<Identifier, MachineStructureDefinition> structures,
            Map<Identifier, MachineRecipe> recipes) {
        synchronized (RuntimeContentVersion.lock()) {
            return commitDynamicLocked(structures, recipes);
        }
    }

    public static void replaceDataPackRecipes(Map<Identifier, MachineRecipe> recipes) {
        synchronized (RuntimeContentVersion.lock()) {
            replaceDataPackLocked(recipes);
        }
    }

    public static RuntimeContentSnapshot replaceDataPackRecipesAndSnapshot(
            Map<Identifier, MachineRecipe> recipes) {
        synchronized (RuntimeContentVersion.lock()) {
            Map<Identifier, MachineRecipe> previous = RecipeRegistry.dataPackSnapshot();
            boolean published = false;
            try {
                RecipeRegistry.validateDataPackCandidate(recipes);
                replaceDataPackLocked(recipes);
                published = true;
                return snapshotLocked();
            } catch (RuntimeException | Error failure) {
                if (published) {
                    try {
                        replaceDataPackLocked(previous);
                    } catch (RuntimeException | Error rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            }
        }
    }

    public static RuntimeContentSnapshot replaceKubeJSRecipesAndSnapshot(
            Map<Identifier, MachineRecipe> recipes) {
        synchronized (RuntimeContentVersion.lock()) {
            Map<Identifier, MachineRecipe> previous = RecipeRegistry.kubeJSSnapshot();
            boolean published = false;
            try {
                RecipeRegistry.replaceKubeJS(recipes);
                published = true;
                return snapshotLocked();
            } catch (RuntimeException | Error failure) {
                if (published) {
                    try {
                        RecipeRegistry.replaceKubeJS(previous);
                    } catch (RuntimeException | Error rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            }
        }
    }

    public static RuntimeContentSnapshot createSnapshot() {
        synchronized (RuntimeContentVersion.lock()) {
            return snapshotLocked();
        }
    }

    private static RuntimeContentSnapshot snapshotLocked() {
        return new RuntimeContentSnapshot(
                MachineStructureRegistry.effectiveSnapshot(),
                RecipeRegistry.effectiveSnapshot(),
                ControllerSpecSync.createSnapshot(),
                ControllerSpecSync.createAppearanceSnapshot(),
                RuntimeContentVersion.current());
    }

    private static void replaceDataPackLocked(Map<Identifier, MachineRecipe> recipes) {
        RecipeRegistry.replaceDataPack(recipes);
    }

    private static void validate(Map<Identifier, MachineStructureDefinition> structures,
                                 Map<Identifier, MachineRecipe> recipes) {
        Map<Identifier, MachineRegistration> registrations = new LinkedHashMap<>();
        for (Map.Entry<Identifier, MachineStructureDefinition> entry : structures.entrySet()) {
            Identifier id = entry.getKey();
            MachineStructureDefinition structure = entry.getValue();
            MachineRegistration registration = MachineDefinitions.getRegistration(id);
            if (registration == null) {
                throw new IllegalStateException("No startup machine registration for structure: " + id);
            }
            if (!id.equals(structure.machineId())) {
                throw new IllegalStateException("Structure key does not match machine id: " + id + " != " + structure.machineId());
            }
            structure.declarations().forEach(declaration -> declaration.requirements().modifierReplacements().values()
                    .stream().flatMap(Collection::stream)
                    .forEach(replacement -> {
                        Identifier modifierId = replacement.getModifierId();
                        if (ModifierRegistry.get(modifierId) == null) {
                            throw new IllegalStateException("Structure " + id
                                    + " refers to unknown machine modifier " + modifierId);
                        }
                    }));
            registrations.put(id, registration.withPattern(structure.pattern()));
        }
        MachineRoleValidator.validate(registrations.values(), null);
        MachineRoleValidator.validateCouplerCounts(registrations.values(), id -> {
            MachineStructureDefinition structure = structures.get(id);
            return structure == null ? null : structure.pattern();
        });
        for (Map.Entry<Identifier, MachineRecipe> entry : recipes.entrySet()) {
            Identifier recipeId = entry.getKey();
            MachineRecipe recipe = entry.getValue();
            if (!recipeId.equals(recipe.id())) {
                throw new IllegalStateException("Recipe key does not match recipe id: "
                        + recipeId + " != " + recipe.id());
            }
            if (RecipeRegistry.containsStatic(recipe.id())) {
                throw new IllegalStateException("Dynamic recipe conflicts with static recipe: " + recipe.id());
            }
            if (RecipeRegistry.dataPackSnapshot().containsKey(recipe.id())) {
                throw new IllegalStateException("Dynamic recipe conflicts with data-pack recipe: " + recipe.id());
            }
        }
    }

    private static boolean recipePoolAvailable(Identifier recipePoolId,
                                               Map<Identifier, MachineStructureDefinition> structures) {
        if (MachineRegistry.containsStatic(recipePoolId)) return true;
        if (structures.containsKey(recipePoolId)
                || MachineStructureRegistry.startupSnapshot().containsKey(recipePoolId)) return true;
        return MachineDefinitions.allRegistrations().stream()
                .filter(registration -> recipePoolId.equals(registration.recipePoolId()))
                .anyMatch(registration -> structures.containsKey(registration.id())
                        || MachineStructureRegistry.startupSnapshot().containsKey(registration.id())
                        || MachineRegistry.containsStatic(registration.id()));
    }

    public record CommitResult(DynamicContentReloadService.ReloadResult result,
                               RuntimeContentSnapshot snapshot) {
    }
}
