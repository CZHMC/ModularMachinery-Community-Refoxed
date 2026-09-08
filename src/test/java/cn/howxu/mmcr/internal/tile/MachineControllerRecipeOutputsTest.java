package cn.howxu.mmcr.internal.tile;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineOutputAmount;
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
        List<MachineOutputAmount> merged = controller.recipeOutputs();
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).amount()).isEqualTo(10L);
        assertThat(((MachineOutput.ItemOutput) merged.get(0).output()).stack().getCount()).isEqualTo(4);
    }

    @Test
    void ordinaryMachineParallelismKeepsLongOutputAmount() {
        MachineControllerBlockEntity controller = TestBootstrap.newController();
        TestBootstrap.bindCraftingWithOutputs(controller,
                List.of(new MachineOutput.ItemOutput(new ItemStack(Items.STONE, Integer.MAX_VALUE), 1F)), 2L);

        assertThat(controller.recipeOutputs()).singleElement()
                .satisfies(output -> assertThat(output.amount()).isEqualTo((long) Integer.MAX_VALUE * 2L));
    }

    @Test
    void ordinaryMachineWithoutParallelismKeepsTheStackAmount() {
        MachineControllerBlockEntity controller = TestBootstrap.newController();
        TestBootstrap.bindCraftingWithOutputs(controller,
                List.of(new MachineOutput.ItemOutput(new ItemStack(Items.STONE, 4), 1F)), 1L);

        assertThat(controller.recipeOutputs()).singleElement()
                .satisfies(output -> assertThat(output.amount()).isEqualTo(4L));
    }

    @Test
    void factoryThreadsAggregateLongOutputAmounts() {
        MachineControllerBlockEntity controller = TestBootstrap.newController();
        MachineOutput output = new MachineOutput.ItemOutput(new ItemStack(Items.STONE, Integer.MAX_VALUE), 1F);
        TestBootstrap.bindFactoryWithOutputs(controller, List.of(output), 2L);
        TestBootstrap.bindFactoryWithOutputs(controller, List.of(output), 2L);

        assertThat(controller.recipeOutputs()).singleElement()
                .satisfies(value -> assertThat(value.amount()).isEqualTo((long) Integer.MAX_VALUE * 4L));
    }

    @Test
    void outputAmountSaturatesAtLongMaximumAfterParallelScaling() {
        MachineControllerBlockEntity controller = TestBootstrap.newController();
        TestBootstrap.bindCraftingWithOutputs(controller,
                List.of(new MachineOutput.ItemOutput(new ItemStack(Items.STONE, Integer.MAX_VALUE), 1F)),
                Long.MAX_VALUE);

        assertThat(controller.recipeOutputs()).singleElement()
                .satisfies(output -> assertThat(output.amount()).isEqualTo(Long.MAX_VALUE));
    }
}
