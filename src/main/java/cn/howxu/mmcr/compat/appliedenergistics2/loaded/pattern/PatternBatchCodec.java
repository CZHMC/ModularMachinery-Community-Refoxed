package cn.howxu.mmcr.compat.appliedenergistics2.loaded.pattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.internal.item.PatternBatchData;
import cn.howxu.mmcr.registry.ModDataComponents;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Restores virtual definitions through the original AE pattern decoding protocol.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class PatternBatchCodec {
    private PatternBatchCodec() {
    }

    /**
     * Unmarked definitions are not ours. Invalid marked definitions throw: returning null would allow
     * a later decoder to reinterpret them as unscaled tasks, or let AE silently discard a saved task.
     */
    public static @Nullable PatternBatchDetails decode(@Nullable AEItemKey definition, Level level) {
        if (definition == null) return null;
        PatternBatchData data = definition.get(ModDataComponents.PATTERN_BATCH.get());
        if (data == null) return null;
        if (data.version() != PatternBatchData.VERSION) {
            throw new IllegalArgumentException("Unsupported pattern batch version: " + data.version());
        }
        var recipe = RecipeRegistry.getRecipe(data.recipeId());
        if (recipe == null || !recipe.recipePoolId().equals(data.recipePoolId())) {
            throw new IllegalArgumentException("Unknown pattern batch binding: " + data.recipePoolId() + "/" + data.recipeId());
        }
        var stack = definition.toStack();
        stack.remove(ModDataComponents.PATTERN_BATCH.get());
        AEItemKey base = AEItemKey.of(stack);
        IPatternDetails original = PatternDetailsHelper.decodePattern(base, level);
        if (original == null) throw new IllegalArgumentException("Pattern batch has no decodable base pattern");
        PatternBatchDetails restored = new PatternBatchDetails(original, data);
        if (!restored.getDefinition().equals(definition)) {
            throw new IllegalArgumentException("Base pattern decoder changed the batch definition");
        }
        return restored;
    }
}
