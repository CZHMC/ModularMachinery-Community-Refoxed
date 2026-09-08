package cn.howxu.mmcr.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

/**
 * Renders the client-neutral chemical state supplied by the loaded Mekanism menu.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class ChemicalGuiRenderer {
    private ChemicalGuiRenderer() {
    }

    public record ChemicalRenderState(Identifier identifier, int tint, int fillHeight) {
    }

    public static ChemicalRenderState state(Identifier identifier, int tint, long amount, long capacity, int height) {
        int fillHeight = identifier == null ? 0 : FluidGuiRenderer.fillHeight(amount, capacity, height);
        return new ChemicalRenderState(identifier, tint, fillHeight);
    }

    public static void drawChemical(GuiGraphicsExtractor graphics, ChemicalRenderState state,
                                    int x, int y, int width, int height) {
        if (state.identifier() == null || state.fillHeight() <= 0 || width <= 0 || height <= 0) return;
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager()
                .getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(state.identifier());
        int fillHeight = Math.min(state.fillHeight(), height);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y + height - fillHeight,
                width, fillHeight, state.tint());
    }
}
