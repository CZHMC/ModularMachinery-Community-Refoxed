package cn.howxu.mmcr.api.publicapi.recipe;

import cn.howxu.mmcr.api.publicapi.recipe.requirement.MachineRequirement;
import java.util.Objects;

/** Immutable public energy recipe requirement.
 * @author howxu <dev@howxu.cn>
 */
public record EnergyRequirement(RecipeIo io, long fePerTick) implements MachineRequirement {
    public EnergyRequirement(long fePerTick) {
        this(RecipeIo.INPUT, fePerTick);
    }

    public EnergyRequirement {
        Objects.requireNonNull(io, "io");
        if (fePerTick < 1 || fePerTick > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Energy per tick must be in [1, Integer.MAX_VALUE]");
        }
    }
}
