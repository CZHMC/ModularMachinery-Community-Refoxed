package cn.howxu.mmcr.api.publicapi.machine;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.publicapi.recipe.RecipeRequirement;
import cn.howxu.mmcr.internal.registration.MachineRecipeConverter;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;

import java.util.List;
import java.util.Objects;

/**
 * Context supplied before a recipe's per-tick input plan.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class RecipeTickContext {
    private final MachineBehaviorContext machineContext;
    private final MachineRecipe recipe;
    private final int currentTick;
    private final int totalTick;
    private final long parallelism;
    private final List<RecipeRequirement> requirements;
    private final List<MachineOutput> outputs;
    private final CapabilitySnapshot capabilitySnapshot;

    public RecipeTickContext(MachineRecipe recipe, int currentTick, int totalTick, long parallelism) {
        this(MachineBehaviorContext.empty(Objects.requireNonNull(recipe, "recipe").recipePoolId()), recipe,
                currentTick, totalTick, parallelism,
                MachineRecipeConverter.toPublicRequirements(recipe.runtimeRequirements()), recipe.runtimeMachineOutputs(),
                new CapabilitySnapshot(List.of()));
    }

    public RecipeTickContext(MachineBehaviorContext machineContext, MachineRecipe recipe, int currentTick,
                             int totalTick, long parallelism,
                             List<RecipeRequirement> requirements,
                             List<MachineOutput> outputs) {
        this(machineContext, recipe, currentTick, totalTick, parallelism, requirements, outputs,
                new CapabilitySnapshot(List.of()));
    }

    public RecipeTickContext(MachineBehaviorContext machineContext, MachineRecipe recipe, int currentTick,
                             int totalTick, long parallelism,
                             List<RecipeRequirement> requirements,
                             List<MachineOutput> outputs, CapabilitySnapshot capabilitySnapshot) {
        this.machineContext = Objects.requireNonNull(machineContext, "machineContext");
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        if (currentTick < 0) throw new IllegalArgumentException("currentTick must not be negative");
        if (totalTick <= 0) throw new IllegalArgumentException("totalTick must be positive");
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism must be positive");
        this.currentTick = currentTick;
        this.totalTick = totalTick;
        this.parallelism = parallelism;
        this.requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        this.outputs = MachineOutput.copyList(Objects.requireNonNull(outputs, "outputs"));
        this.capabilitySnapshot = Objects.requireNonNull(capabilitySnapshot, "capabilitySnapshot");
    }

    public MachineRecipe recipe() {
        return recipe;
    }

    public MachineBehaviorContext machineContext() {
        return machineContext;
    }

    public int currentTick() {
        return currentTick;
    }

    public int totalTick() {
        return totalTick;
    }

    public long parallelism() {
        return parallelism;
    }

    public List<RecipeRequirement> requirements() {
        return requirements;
    }

    public List<MachineOutput> outputs() {
        return outputs;
    }

    public CapabilitySnapshot capabilitySnapshot() {
        return capabilitySnapshot;
    }
}
