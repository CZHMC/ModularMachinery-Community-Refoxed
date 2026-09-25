package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import cn.howxu.mmcr.api.recipe.EffectiveRecipe;
import cn.howxu.mmcr.api.recipe.EffectiveRecipeResolver;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;

import java.util.List;

/** Immutable worker result for all effective candidates of one controller version key.
 * @author howxu <dev@howxu.cn>
 */
public record EffectiveRecipeSet(Key key, List<EffectiveRecipe> recipes) {
    public EffectiveRecipeSet {
        recipes = List.copyOf(recipes == null ? List.of() : recipes);
    }

    public static EffectiveRecipeSet resolve(ControllerRuntimeSnapshot snapshot, long catalogVersion,
                                             List<MachineRecipe> recipes, List<MachineModifier> modifiers) {
        Key key = new Key(snapshot.structure().version(), snapshot.capabilityVersion(), snapshot.modifierVersion(),
                snapshot.stateVersion(), catalogVersion);
        EffectiveRecipeResolver resolver = new EffectiveRecipeResolver();
        return new EffectiveRecipeSet(key, (recipes == null ? List.<MachineRecipe>of() : recipes).stream()
                .map(recipe -> resolver.resolve(recipe, snapshot, modifiers)).toList());
    }

    public record Key(long structureVersion, long capabilityVersion, long modifierVersion,
                      long stateVersion, long catalogVersion) {
    }

    /** Controller-local cache shared by async searches for the same immutable input versions. */
    public static final class Cache {
        private @org.jetbrains.annotations.Nullable Key key;
        private List<MachineRecipe> sources = List.of();
        private List<MachineModifier> modifiers = List.of();
        private @org.jetbrains.annotations.Nullable EffectiveRecipeSet value;

        public synchronized EffectiveRecipeSet resolve(ControllerRuntimeSnapshot snapshot, long catalogVersion,
                                                       List<MachineRecipe> recipes,
                                                       List<MachineModifier> machineModifiers) {
            List<MachineRecipe> copiedRecipes = List.copyOf(recipes == null ? List.of() : recipes);
            List<MachineModifier> copiedModifiers = List.copyOf(
                    machineModifiers == null ? List.of() : machineModifiers);
            Key requestedKey = new Key(snapshot.structure().version(), snapshot.capabilityVersion(),
                    snapshot.modifierVersion(), snapshot.stateVersion(), catalogVersion);
            if (requestedKey.equals(key) && copiedRecipes.equals(sources)
                    && copiedModifiers.equals(modifiers) && value != null) return value;
            EffectiveRecipeSet resolved = EffectiveRecipeSet.resolve(snapshot, catalogVersion, copiedRecipes,
                    copiedModifiers);
            key = requestedKey;
            sources = copiedRecipes;
            modifiers = copiedModifiers;
            value = resolved;
            return resolved;
        }
    }
}
