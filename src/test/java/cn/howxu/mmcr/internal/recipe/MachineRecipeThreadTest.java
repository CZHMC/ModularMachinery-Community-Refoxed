package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.internal.runtime.CraftingRuntime;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.ServerThreadTestScope;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates the actual lastRecipe cache and its persisted request-only provenance.
 * @author howxu <dev@howxu.cn>
 */
class MachineRecipeThreadTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrap();
        TestBootstrap.bootstrapCapabilities();
    }

    @AfterEach
    void cleanup() { RecipeRegistry.clearForTesting(); }

    @Test
    void patternMemoryReloadCannotArmAnAutomaticRestartAndChangedDefinitionInvalidatesIt() throws Exception {
        try (var ignored = ServerThreadTestScope.open()) {
            var controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"));
            controller.setFormed(true);
            var runtime = new CraftingRuntime(controller, controller.componentRuntime());
            var thread = new MachineRecipeThread(controller, runtime);
            var recipe = recipe(1);
            RecipeRegistry.replaceDynamic(Map.of(recipe.id(), recipe));
            var prepared = runtime.prepareExactPatternStart(recipe, 1L, List.of(), null);
            assertThat(prepared).isNotNull();
            assertThat(runtime.reservePatternStart()).isTrue();
            assertThat(runtime.commitPatternStart(prepared)).isTrue();
            thread.rememberStartedRecipe();
            assertThat(thread.cachedPatternRecipe(recipe.id())).isSameAs(recipe);
            thread.markRecipeFinished();
            assertThat(thread.hasRestartPending()).isFalse();

            var lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, lookup);
            thread.savePatternMemory(output);
            var restored = new MachineRecipeThread(controller);
            restored.loadPatternMemory(TagValueInput.create(ProblemReporter.DISCARDING, lookup, output.buildResult()));
            assertThat(restored.cachedPatternRecipe(recipe.id())).isSameAs(recipe);
            restored.markRecipeFinished();
            assertThat(restored.hasRestartPending()).isFalse();
            var changed = recipe(2);
            RecipeRegistry.replaceDynamic(Map.of(changed.id(), changed));
            assertThat(restored.cachedPatternRecipe(recipe.id())).isNull();
        }
    }

    private static MachineRecipe recipe(int duration) {
        return MachineRecipe.fromCanonical(MMCR.id("normal_pattern_cache"), MMCR.id("test_cube"), duration,
                List.of(), List.of(), List.of(), 0, 1, false, false, false, Set.of());
    }
}
