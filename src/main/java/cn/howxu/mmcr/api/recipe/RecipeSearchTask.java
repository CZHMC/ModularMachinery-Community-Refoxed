package cn.howxu.mmcr.api.recipe;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.plan.PlanningResult;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.StatusSeverity;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.machine.level.MachineLevel;
import cn.howxu.mmcr.api.machine.level.MachineLevelRegistry;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Searches published controller state with the current execution capabilities and returns a recipe handle.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class RecipeSearchTask {
    static final int LEVEL_FAILURE_PRIORITY = 2;
    private final ControllerRuntimeSnapshot snapshot;
    private final Identifier machineId;
    private final long structureVersion;
    private final long maxParallelism;
    private final List<MachineRecipe> candidates;
    private final @Nullable Identifier lockedRecipeId;
    private final List<MachineCapability> capabilities;
    private final List<RecipeModifier> modifiers;

    public RecipeSearchTask(ControllerRuntimeSnapshot snapshot, Identifier machineId, long structureVersion,
                            long maxParallelism, List<MachineRecipe> candidates,
                            @Nullable Identifier lockedRecipeId, List<MachineCapability> capabilities) {
        this(snapshot, machineId, structureVersion, maxParallelism, orderedCandidates(candidates),
                lockedRecipeId, capabilities, flattenModifiers(snapshot));
    }

    public RecipeSearchTask(ControllerRuntimeSnapshot snapshot, Identifier machineId, long structureVersion,
                            long maxParallelism, List<MachineRecipe> orderedCandidates,
                            @Nullable Identifier lockedRecipeId, List<MachineCapability> capabilities,
                            List<RecipeModifier> modifiers) {
        if (snapshot == null || machineId == null) throw new IllegalArgumentException("snapshot and machineId are required");
        this.snapshot = snapshot;
        this.machineId = machineId;
        this.structureVersion = structureVersion;
        this.maxParallelism = Math.max(1L, maxParallelism);
        this.candidates = poolCandidates(machineId, orderedCandidates);
        this.lockedRecipeId = lockedRecipeId;
        this.capabilities = List.copyOf(capabilities == null ? List.of() : capabilities);
        this.modifiers = List.copyOf(modifiers == null ? List.of() : modifiers);
    }

    public RecipeSearchResult compute() {
        @Nullable String bestFailureUnloc = null;
        @Nullable ExecutionStatus bestFailure = null;
        @Nullable PlanningResult bestPlanningResult = null;
        @Nullable LevelInsufficientFailure bestLevelFailure = null;
        float bestValidity = 0.0F;
        List<MachineRecipe> ordered = searchCandidates();

        for (int recipeIndex = 0; recipeIndex < ordered.size(); recipeIndex++) {
            MachineRecipe recipe = ordered.get(recipeIndex);
            if (!snapshot.moduleConnectionStatus().canRunRecipe(recipe.requiredHostIds())) {
                ExecutionStatus moduleFailure = new ExecutionStatus(MMCR.id("crafting_runtime"),
                        StatusSeverity.BLOCKED, MMCR.id("crafting_runtime"), Map.of("reason", "module_connection"));
                float validity = validity(moduleFailure);
                if (preferFailure(moduleFailure, validity, bestFailure, bestValidity)) {
                    bestValidity = validity;
                    bestFailure = moduleFailure;
                    bestFailureUnloc = failureUnloc(moduleFailure);
                }
                continue;
            }
            PlanningResult result = planStart(recipe);
            if (!result.successful()) {
                float validity = validity(result);
                if (preferFailure(result.failure(), validity, bestFailure, bestValidity)) {
                    bestValidity = validity;
                    bestFailure = result.failure();
                    bestFailureUnloc = failureUnloc(result.failure());
                    bestPlanningResult = result;
                }
                continue;
            }
            LevelInsufficientFailure levelFailure = levelFailure(recipe);
            if (levelFailure == null) {
                boolean conflictProne = lockedRecipeId == null
                        && hasMoreSpecificPendingInputCandidate(recipe, recipeIndex, ordered);
                return RecipeSearchResult.success(recipe, machineId, structureVersion,
                        snapshot.capabilityVersion(), snapshot.modifierVersion(), result, conflictProne);
            }
            if (bestLevelFailure == null) bestLevelFailure = levelFailure;
        }
        if (bestLevelFailure != null && (bestFailure == null
                || LEVEL_FAILURE_PRIORITY < failurePriority(bestFailure))) {
            return RecipeSearchResult.levelFailure(machineId, structureVersion,
                    snapshot.capabilityVersion(), snapshot.modifierVersion(), bestLevelFailure);
        }
        if (bestFailure != null) {
            return RecipeSearchResult.failure(machineId, structureVersion,
                    snapshot.capabilityVersion(), snapshot.modifierVersion(), bestPlanningResult,
                    bestFailureUnloc, bestFailure, bestValidity);
        }
        return RecipeSearchResult.failure(machineId, structureVersion,
                snapshot.capabilityVersion(), snapshot.modifierVersion(), bestPlanningResult,
                bestFailureUnloc, bestFailure, bestValidity);
    }

    private PlanningResult planStart(MachineRecipe recipe) {
        CraftingContext context = borrowContext(recipe);
        try {
            return context.planStartResult(recipe, maxParallelism);
        } finally {
            CraftingContextPool.global().returnContext(recipe.id(), context);
        }
    }

    private CraftingContext borrowContext(MachineRecipe recipe) {
        return CraftingContextPool.global().borrow(recipe.id(), new CapabilitySnapshot(capabilities), modifiers);
    }

    private @Nullable LevelInsufficientFailure levelFailure(MachineRecipe recipe) {
        for (LevelRequirement requirement : recipe.levelRequirements()) {
            MachineLevel required = MachineLevelRegistry.getLevel(requirement.levelId());
            MachineLevel actual = snapshot.foundLevels().get(requirement.typeId());
            if (required == null || actual == null || actual.priority() < required.priority()) {
                return new LevelInsufficientFailure(requirement.typeId(), requirement.levelId(),
                        actual == null ? null : actual.id());
            }
        }
        return null;
    }

    private static List<MachineRecipe> orderedCandidates(List<MachineRecipe> values) {
        return (values == null ? List.<MachineRecipe>of() : values).stream()
                .sorted(Comparator.comparingInt(MachineRecipe::priority)
                        .thenComparing(Comparator.comparingInt(MachineRecipe::inputRequirementCount).reversed())
                        .thenComparing(MachineRecipe::id))
                .toList();
    }

    private static List<MachineRecipe> poolCandidates(Identifier machineId, List<MachineRecipe> candidates) {
        Identifier recipePoolId = MachineRegistry.recipePoolForMachine(machineId);
        if (recipePoolId == null || candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream().filter(recipe -> recipe != null
                && recipePoolId.equals(recipe.recipePoolId())).toList();
    }

    private static List<RecipeModifier> flattenModifiers(ControllerRuntimeSnapshot snapshot) {
        return snapshot == null ? List.of()
                : snapshot.foundModifiers().values().stream().flatMap(List::stream).toList();
    }

    private List<MachineRecipe> searchCandidates() {
        if (lockedRecipeId == null) return candidates;
        return candidates.stream().filter(recipe -> lockedRecipeId.equals(recipe.id())).findFirst().map(List::of)
                .orElseGet(List::of);
    }

    private boolean hasMoreSpecificPendingInputCandidate(MachineRecipe selectedRecipe, int selectedIndex,
                                                          List<MachineRecipe> ordered) {
        for (int index = 0; index < selectedIndex; index++) {
            MachineRecipe earlier = ordered.get(index);
            if (earlier.priority() != selectedRecipe.priority()
                    || earlier.inputRequirementCount() <= selectedRecipe.inputRequirementCount()
                    || !earlier.hasOverlappingInputs(selectedRecipe)) continue;
            if (!snapshot.moduleConnectionStatus().canRunRecipe(earlier.requiredHostIds())) continue;
            CraftingContext context = borrowContext(earlier);
            PlanningResult inputs;
            PlanningResult outputs;
            try {
                inputs = context.planInputs(earlier, maxParallelism);
                outputs = context.planOutputs(earlier, maxParallelism);
            } finally {
                CraftingContextPool.global().returnContext(earlier.id(), context);
            }
            if (!inputs.successful() && outputs.successful()) return true;
        }
        return false;
    }

    private static float validity(@Nullable ExecutionStatus failure) {
        if (failure == null) return 0.0F;
        return failure.severity() == StatusSeverity.BLOCKED ? 0.5F : 0.1F;
    }

    private static float validity(PlanningResult result) {
        if (result == null) return 0.0F;
        int completedRequirements = result.failureRequirementIndex() == null ? 0 : result.failureRequirementIndex();
        return completedRequirements + validity(result.failure());
    }

    static int failurePriority(@Nullable ExecutionStatus failure) {
        if (failure == null) return Integer.MAX_VALUE;
        return switch (failure.details().getOrDefault("reason", "")) {
            case "insufficient_resource" -> 0;
            case "insufficient_energy" -> 1;
            default -> 3;
        };
    }

    private static boolean preferFailure(@Nullable ExecutionStatus candidate, float candidateValidity,
                                         @Nullable ExecutionStatus current, float currentValidity) {
        if (current == null) return true;
        int candidatePriority = failurePriority(candidate);
        int currentPriority = failurePriority(current);
        return candidateValidity > currentValidity
                || candidateValidity == currentValidity && candidatePriority < currentPriority;
    }

    private static @Nullable String failureUnloc(@Nullable ExecutionStatus failure) {
        if (failure == null) return null;
        return switch (failure.details().getOrDefault("reason", "")) {
            case "insufficient_resource" -> "gui.mmcr.controller.failure.missing_input";
            case "insufficient_energy" -> "gui.mmcr.controller.failure.missing_energy";
            case "no_output_capacity" -> "gui.mmcr.controller.failure.missing_output";
            case "module_connection" -> "gui.mmcr.controller.failure.module_connection";
            default -> "gui.mmcr.controller.failure.missing_input";
        };
    }
}
