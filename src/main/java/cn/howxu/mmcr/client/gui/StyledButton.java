package cn.howxu.mmcr.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Shared button presentation for MMCR screens.
 *
 * @author howxu <dev@howxu.cn>
 */
public class StyledButton extends Button {
    @Nullable
    private Identifier icon;

    public StyledButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    public void setIcon(Identifier icon) {
        this.icon = icon;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawBackground(graphics);
        if (icon != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, icon, getX(), getY(), 0, 0,
                    getWidth(), getHeight(), 16, 16, 16, 16);
        } else {
            extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        }
    }

    protected void drawBackground(GuiGraphicsExtractor graphics) {
        int baseColor = active ? 0xFF9A9FB4 : 0xFF8E92A2;
        int borderColor = isHoveredOrFocused() ? 0xFFF2F3F3 : 0xFFCBCCD4;
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), borderColor);
        graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, baseColor);
    }
}
