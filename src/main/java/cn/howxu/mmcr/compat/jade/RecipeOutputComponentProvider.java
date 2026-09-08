package cn.howxu.mmcr.compat.jade;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineOutputAmount;
import cn.howxu.mmcr.util.ReadableNumber;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.TooltipPosition;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.fluid.JadeFluidObject;
import snownee.jade.api.ui.JadeUI;
import snownee.jade.overlay.DisplayHelper;

import java.util.List;

/**
 * Renders the controller's current recipe outputs at the tail of the Jade tooltip.
 *
 * @author howxu <dev@howxu.cn>
 */
public enum RecipeOutputComponentProvider implements IComponentProvider<BlockAccessor> {
    INSTANCE;

    public static final Identifier UID = MMCR.id("recipe_output");

    @Override
    public Identifier getUid() {
        return UID;
    }

    @Override
    public int getDefaultPriority() {
        return TooltipPosition.TAIL - 9;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        List<MachineOutputAmount> outputs = RecipeOutputCodec.read(accessor.getServerData());
        if (outputs.isEmpty()) return;
        tooltip.add(Component.translatable("jade.mmcr.machine_controller.recipe_output"));
        for (MachineOutputAmount output : outputs) {
            if (output.output() instanceof MachineOutput.ItemOutput item) renderItem(tooltip, item, output.amount());
            else if (output.output() instanceof MachineOutput.FluidOutput fluid) renderFluid(tooltip, fluid, output.amount());
        }
    }

    private static void renderItem(ITooltip tooltip, MachineOutput.ItemOutput item, long amount) {
        ItemStack stack = item.stack();
        if (stack.isEmpty() || amount <= 0L) return;
        ItemStack iconStack = stack.copy();
        iconStack.setCount(1);
        tooltip.add(JadeUI.smallItem(iconStack));
        tooltip.append(JadeUI.spacer(2, 0));
        String count = amount > 1L
                ? ReadableNumber.formatForSlot(amount, 0, "") + " "
                : "";
        Component text = Component.translatable("jade.mmcr.machine_controller.recipe_output.item",
                count,
                stack.getHoverName().copy().withStyle(ChatFormatting.WHITE));
        tooltip.append(text);
    }

    private static void renderFluid(ITooltip tooltip, MachineOutput.FluidOutput fluid, long amount) {
        FluidStack stack = fluid.stack();
        if (stack.isEmpty() || amount <= 0L) return;
        JadeFluidObject obj = JadeFluidObject.of(stack.getFluid(), amount);
        int lineHeight = DisplayHelper.font().lineHeight;
        var icon = JadeUI.fluid(obj);
        icon.setFreeSpace(lineHeight + 1, lineHeight - 1);
        tooltip.add(icon.offset(0, -1));
        tooltip.append(JadeUI.spacer(2, 0));
        String formattedAmount = amount <= 10L
                ? ReadableNumber.formatForSlot(amount, 0, "mB")
                : ReadableNumber.formatForSlot(amount, 3, "B");
        Component name = ComponentUtils.wrapInSquareBrackets(stack.getHoverName())
                .withStyle(ChatFormatting.WHITE);
        tooltip.append(Component.translatable("jade.mmcr.machine_controller.recipe_output.fluid",
                formattedAmount, name));
    }
}
