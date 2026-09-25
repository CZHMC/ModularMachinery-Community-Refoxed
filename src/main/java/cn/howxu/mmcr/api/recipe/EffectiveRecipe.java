package cn.howxu.mmcr.api.recipe;

import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;

import java.util.List;
import java.util.Objects;

/** Immutable execution view of a recipe after machine modifiers are resolved.
 * @author howxu <dev@howxu.cn>
 */
public record EffectiveRecipe(
        MachineRecipe source,
        List<MachineRequirement> requirements,
        List<MachineOutput> outputs,
        int duration,
        long parallelismLimit,
        int factoryThreadLimit,
        int recipeThreadLimit,
        boolean parallelized,
        long structureVersion,
        long capabilityVersion,
        long modifierVersion,
        long stateVersion) {

    public EffectiveRecipe {
        source = Objects.requireNonNull(source, "source");
        requirements = List.copyOf(requirements == null ? List.of() : requirements);
        outputs = MachineOutput.copyList(outputs == null ? List.of() : outputs);
        if (duration < 1 || parallelismLimit < 1L || factoryThreadLimit < 1 || recipeThreadLimit < 1) {
            throw new IllegalArgumentException("Effective recipe limits must be positive");
        }
    }
}
