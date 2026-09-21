package cn.howxu.mmcr.api.publicapi.recipe;

import java.util.Objects;

/**
 * @author howxu <dev@howxu.cn>
 */
public record StageRequirement(RecipeIo io, int minStage) implements RecipeRequirement {
    public StageRequirement(int minStage) {
        this(RecipeIo.INPUT, minStage);
    }

    public StageRequirement {
        Objects.requireNonNull(io, "io");
        if (io != RecipeIo.INPUT) {
            throw new IllegalArgumentException("Stage requirements must use input direction");
        }
        if (minStage < 1) throw new IllegalArgumentException("Stage minimum must be at least 1");
    }
}
