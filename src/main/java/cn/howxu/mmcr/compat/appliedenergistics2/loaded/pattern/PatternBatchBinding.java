package cn.howxu.mmcr.compat.appliedenergistics2.loaded.pattern;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/** Stable recipe identity and recipe executions represented by one original pattern.
 * @author howxu <dev@howxu.cn>
 */
public record PatternBatchBinding(Identifier recipePoolId, Identifier recipeId, long recipeCraftsPerPattern) {
    public PatternBatchBinding {
        Objects.requireNonNull(recipePoolId, "recipePoolId");
        Objects.requireNonNull(recipeId, "recipeId");
        if (recipeCraftsPerPattern <= 0L) throw new IllegalArgumentException("Recipe crafts must be positive");
    }
}
