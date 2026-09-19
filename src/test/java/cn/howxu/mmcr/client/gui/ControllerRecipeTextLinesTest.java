package cn.howxu.mmcr.client.gui;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineOutputAmount;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies controller recipe output text construction.
 *
 * @author howxu <dev@howxu.cn>
 */
class ControllerRecipeTextLinesTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void outputOverflowUsesTheLastOfSixtyFourLinesForTheMoreMarker() {
        List<MachineOutputAmount> outputs = IntStream.range(0, 65)
                .mapToObj(index -> new MachineOutputAmount(
                        new MachineOutput.ItemOutput(new ItemStack(Items.STONE, index + 1), 1F), index + 1L))
                .toList();

        List<ControllerTextLine> lines = ControllerRecipeTextLines.outputs(outputs);

        assertThat(lines).hasSize(64);
        assertThat(lines.getLast().text())
                .isEqualTo(Component.translatable("gui.mmcr.controller.recipe_output.more"));
    }

    @Test
    void itemOutputUsesItsStackAsTheNativeIcon() {
        ItemStack stack = new ItemStack(Items.DIAMOND, 3);

        ControllerTextLine line = ControllerRecipeTextLines.outputs(List.of(
                new MachineOutputAmount(new MachineOutput.ItemOutput(stack, 1F), 3L))).getFirst();

        assertThat(line.icon()).isInstanceOfSatisfying(ControllerTextLine.ItemIcon.class,
                icon -> assertThat(icon.stack().is(Items.DIAMOND)).isTrue());
    }

    @Test
    void plainTextDoesNotReserveSpaceForAnIcon() {
        assertThat(new ControllerTextLine(Component.literal("external"), 0xFFFFFFFF).textXOffset()).isZero();
    }

    @Test
    void recipeOutputsAreScaledByTheSelectedRuntimeParallelism() {
        MachineRecipe recipe = new MachineRecipe(MMCR.id("controller_recipe_text"), MMCR.id("test_cube"), 1,
                List.of(), List.of(new MachineOutput.ItemOutput(new ItemStack(Items.DIAMOND, 2), 1F)),
                List.of(), 0, 1, false, false, false, Set.of());

        assertThat(ControllerRecipeTextLines.forRecipe(recipe, 3L))
                .extracting(ControllerTextLine::text)
                .anySatisfy(text -> assertThat(text.getString()).contains("6"));
    }
}
