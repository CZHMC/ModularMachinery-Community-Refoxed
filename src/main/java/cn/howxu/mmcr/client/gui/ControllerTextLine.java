package cn.howxu.mmcr.client.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * One logical controller screen line and its internal render color.
 *
 * @param text the line text
 * @param color the render color
 * @param icon optional native resource icon rendered before the text
 * @param tooltip tooltip shown for the line or its icon
 * @author howxu <dev@howxu.cn>
 */
public record ControllerTextLine(Component text, int color, Icon icon, List<Component> tooltip) {
    public ControllerTextLine(Component text, int color) {
        this(text, color, null, List.of());
    }

    public ControllerTextLine {
        tooltip = List.copyOf(tooltip == null ? List.of() : tooltip);
    }

    public int textXOffset() {
        return icon == null ? 0 : icon.width() + 2;
    }

    public sealed interface Icon permits ItemIcon {
        int width();
    }

    public record ItemIcon(ItemStack stack) implements Icon {
        @Override
        public int width() {
            return 10;
        }
    }
}
