package cn.howxu.mmcr.internal.tile;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link MachineControllerBlockEntity#recipeOutputs()} falls back to the
 * controller's {@code CraftingRuntime.activeOutputs()} when no factory lane is active and
 * aggregates per-lane outputs when factory lanes are present.
 *
 * @author howxu <dev@howxu.cn>
 */
class MachineControllerRecipeOutputsTest {

    @BeforeAll
    static void bootstrap() throws Exception { TestBootstrap.bootstrap(); }

    @Test
    void emptyControllerReturnsEmptyOutputs() {
        MachineControllerBlockEntity controller = TestBootstrap.newController();
        assertThat(controller.recipeOutputs()).isEmpty();
    }

    @Test
    void factoryAggregationMergesIdenticalItems() {
        MachineControllerBlockEntity controller = TestBootstrap.newController();
        TestBootstrap.bindFactoryWithOutputs(controller, List.of(
                new MachineOutput.ItemOutput(new ItemStack(Items.STONE, 4), 1F),
                new MachineOutput.ItemOutput(new ItemStack(Items.STONE, 6), 1F)));
        List<MachineOutput> merged = controller.recipeOutputs();
        assertThat(merged).hasSize(1);
        assertThat(((MachineOutput.ItemOutput) merged.get(0)).stack().getCount()).isEqualTo(10);
    }
}
