package cn.howxu.mmcr.client.gui;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineOutputAmount;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.util.SaturatingLong;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds controller text lines for a recipe's outputs.
 *
 * @author howxu <dev@howxu.cn>
 */
final class ControllerRecipeTextLines {
    static final int MAX_OUTPUT_LINES = 64;

    private ControllerRecipeTextLines() {
    }

    static List<ControllerTextLine> outputs(List<MachineOutputAmount> outputs) {
        List<ControllerTextLine> lines = new ArrayList<>();
        for (MachineOutputAmount output : outputs) {
            if (!isRenderable(output)) continue;
            if (lines.size() == MAX_OUTPUT_LINES - 1) {
                lines.add(new ControllerTextLine(Component.translatable("gui.mmcr.controller.recipe_output.more"),
                        MachineControllerScreen.STATUS_LABEL_COLOR));
                return List.copyOf(lines);
            }
            lines.add(line(output));
        }
        return List.copyOf(lines);
    }

    static List<ControllerTextLine> forRecipe(MachineRecipe recipe, long parallelism) {
        if (recipe == null || parallelism < 1L) return List.of();
        return outputs(recipe.machineOutputs().stream()
                .map(output -> new MachineOutputAmount(output,
                        SaturatingLong.multiply(MachineOutput.scaledAmount(output), parallelism)))
                .toList());
    }

    private static boolean isRenderable(MachineOutputAmount output) {
        if (output.amount() <= 0L) return false;
        if (output.output() instanceof MachineOutput.ItemOutput item) return !item.stack().isEmpty();
        if (output.output() instanceof MachineOutput.FluidOutput fluid) return !fluid.stack().isEmpty();
        return false;
    }

    private static ControllerTextLine line(MachineOutputAmount output) {
        if (output.output() instanceof MachineOutput.ItemOutput item) {
            return new ControllerTextLine(Component.literal(output.amount() + " " + item.stack().getHoverName().getString()),
                    MachineControllerScreen.STATUS_LABEL_COLOR, new ControllerTextLine.ItemIcon(item.stack()),
                    List.of(item.stack().getHoverName()));
        }
        return new ControllerTextLine(Component.literal(output.amount() + " "),
                MachineControllerScreen.STATUS_LABEL_COLOR);
    }
}
