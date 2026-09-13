package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.test.RecipeTestSupport;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.TestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies machine-pool boundaries for ordinary recipe threads.
 *
 * @author howxu <dev@howxu.cn>
 */
class RecipeThreadTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @AfterEach
    void cleanup() {
        RecipeRegistry.clearForTesting();
    }

    @Test
    void ordinary_search_ignores_a_recipe_from_a_different_pool() {
        MachineControllerBlockEntity controller = RuntimeTestFixtures.controller(MMCR.id("test_cube"));
        MachineRecipe foreign = RecipeTestSupport.create(MMCR.id("ordinary_foreign_pool_recipe"),
                MMCR.id("ordinary_foreign_pool"), 20, List.of(), List.of());
        MachineRecipeThread thread = new MachineRecipeThread(controller);

        assertThat(thread.searchAndStartRecipe(List.of(foreign), 1,
                controller.runtimeSnapshot().structure().version())).isFalse();
        assertThat(thread.runtime().active()).isFalse();
        assertThat(thread.runtime().failure()).isNotNull();
        assertThat(thread.runtime().failure().details()).containsEntry("reason", "recipe_search");
    }
}
