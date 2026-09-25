package cn.howxu.mmcr.api.recipe;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import cn.howxu.mmcr.internal.multiblock.ModuleConnectionStatus;
import cn.howxu.mmcr.internal.recipe.EffectiveRecipeSet;
import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;
import cn.howxu.mmcr.internal.runtime.CraftingStateSnapshot;
import cn.howxu.mmcr.internal.runtime.FactorySnapshot;
import cn.howxu.mmcr.internal.runtime.StructureSnapshot;
import cn.howxu.mmcr.test.TestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveRecipeResolverTest {

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void resolves_bounded_duration_threads_and_parallelized_rule() {
        MachineRecipe recipe = MachineRecipe.fromCanonical(MMCR.id("effective_recipe"), MMCR.id("pool"), 20,
                List.of(), List.of(), List.of(), 0, 8, false, true, false, Set.of());
        ControllerRuntimeSnapshot snapshot = new ControllerRuntimeSnapshot(
                StructureSnapshot.empty(), 2L, 3L, 4L, Map.of(), Map.of(), Set.of(),
                ModuleConnectionStatus.disconnected(), 0, CraftingStateSnapshot.empty(0L, 2L, 3L),
                FactorySnapshot.empty(), List.of(), List.of(), List.of(), "", "", 0,
                false, false, 0, 0, 16L);

        EffectiveRecipe effective = new EffectiveRecipeResolver().resolve(recipe, snapshot, List.of(
                MachineModifier.numeric("duration", "input", 0D, "multiply", false),
                MachineModifier.numeric("recipe_threads", "recipe", 2D, "add", false),
                MachineModifier.parallelized(false)));

        assertThat(effective.duration()).isEqualTo(1);
        assertThat(effective.recipeThreadLimit()).isEqualTo(10);
        assertThat(effective.parallelized()).isFalse();
        assertThat(effective.parallelismLimit()).isEqualTo(1L);
        assertThat(effective.modifierVersion()).isEqualTo(3L);
    }

    @Test
    void controller_cache_reuses_one_effective_set_until_a_version_changes() {
        MachineRecipe recipe = MachineRecipe.fromCanonical(MMCR.id("cached_effective_recipe"), MMCR.id("pool"), 20,
                List.of(), List.of(), List.of(), 0, 1, false, true, false, Set.of());
        ControllerRuntimeSnapshot firstSnapshot = snapshot(3L);
        EffectiveRecipeSet.Cache cache = new EffectiveRecipeSet.Cache();

        EffectiveRecipeSet first = cache.resolve(firstSnapshot, 7L, List.of(recipe), List.of());
        EffectiveRecipeSet reused = cache.resolve(firstSnapshot, 7L, List.of(recipe), List.of());
        EffectiveRecipeSet changed = cache.resolve(snapshot(4L), 7L, List.of(recipe), List.of());

        assertThat(reused).isSameAs(first);
        assertThat(changed).isNotSameAs(first);
    }

    private static ControllerRuntimeSnapshot snapshot(long modifierVersion) {
        return new ControllerRuntimeSnapshot(StructureSnapshot.empty(), 2L, modifierVersion, 4L,
                Map.of(), Map.of(), Set.of(), ModuleConnectionStatus.disconnected(), 0,
                CraftingStateSnapshot.empty(0L, 2L, modifierVersion), FactorySnapshot.empty(), List.of(), List.of(),
                List.of(), "", "", 0, false, false, 0, 0, 16L);
    }
}
