package cn.howxu.mmcr.compat.jade;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.impl.ui.ProgressOverlayElement;

/**
 * Renders a Mekanism chemical icon (a 16×16 sprite in the BLOCKS atlas) inside a
 * Jade tooltip line. The {@code tint} argument is the chemical's tint that overlays
 * the sprite; pass {@code -1} for no tint.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class JadeChemicalElement extends ProgressOverlayElement {

    private final Identifier spriteLocation;
    private final int tint;

    public JadeChemicalElement(Identifier spriteLocation, int tint, int size) {
        this.spriteLocation = spriteLocation;
        this.tint = tint;
        width = size;
        height = size;
    }

    @Override
    public Component getNarration() {
        return null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        TextureAtlasSprite sprite = minecraft.getAtlasManager()
                .getAtlasOrThrow(AtlasIds.BLOCKS)
                .getSprite(spriteLocation);
        if (sprite == null) return;
        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;
        int drawX = floatingRect == null ? getX() : (int) floatingRect.getX();
        int drawY = floatingRect == null ? getY() : (int) floatingRect.getY();
        int drawW = floatingRect == null ? width : (int) floatingRect.getWidth();
        int drawH = floatingRect == null ? height : (int) floatingRect.getHeight();
        graphics.blitSprite(pipeline, sprite, drawX, drawY, drawW, drawH, tint);
    }
}