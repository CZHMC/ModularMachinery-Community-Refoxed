package cn.howxu.mmcr.compat.jade;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.recipe.MachineOutput;
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
import snownee.jade.util.FluidTextHelper;

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
        return TooltipPosition.TAIL;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        List<MachineOutput> outputs = RecipeOutputCodec.read(accessor.getServerData());
        if (outputs.isEmpty()) return;
        tooltip.add(Component.translatable("jade.mmcr.machine_controller.recipe_output"));
        for (MachineOutput output : outputs) {
            if (output instanceof MachineOutput.ItemOutput item) renderItem(tooltip, item);
            else if (output instanceof MachineOutput.FluidOutput fluid) renderFluid(tooltip, fluid);
        }
    }

    private static void renderItem(ITooltip tooltip, MachineOutput.ItemOutput item) {
        ItemStack stack = item.stack();
        if (stack.isEmpty()) return;
        tooltip.add(JadeUI.smallItem(stack));
        Component text = Component.translatable("jade.mmcr.machine_controller.recipe_output.item",
                String.valueOf(stack.getCount()),
                stack.getHoverName().copy().withStyle(ChatFormatting.WHITE));
        tooltip.append(text);
    }

    private static void renderFluid(ITooltip tooltip, MachineOutput.FluidOutput fluid) {
        FluidStack stack = fluid.stack();
        if (stack.isEmpty()) return;
        JadeFluidObject obj = JadeFluidObject.of(stack.getFluid(), stack.getAmount());
        tooltip.add(JadeUI.fluid(obj));
        // FluidTextHelper exposes getMillibuckets(long, boolean) → NarratableComponent (which is a Component)
        // on this Jade version. The amount label is rendered as a Component so Jade can apply its own
        // formatter; the fluid name follows in square brackets for visual consistency with the item renderer.
        Component amount = FluidTextHelper.getMillibuckets(stack.getAmount(), true);
        Component name = ComponentUtils.wrapInSquareBrackets(stack.getHoverName())
                .withStyle(ChatFormatting.WHITE);
        tooltip.append(Component.translatable("jade.mmcr.machine_controller.recipe_output.fluid",
                amount, name));
    }
}
