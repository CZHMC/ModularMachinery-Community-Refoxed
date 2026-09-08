package cn.howxu.mmcr.client.gui;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.client.render.FluidGuiRenderer;
import cn.howxu.mmcr.compat.mekanism.loaded.HeatPortMenu;
import cn.howxu.mmcr.util.IOType;
import cn.howxu.mmcr.util.ReadableNumber;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

/**
 * Heat hatch screen with read-only heat and temperature information.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class HeatHatchScreen extends AbstractPortScreen<HeatPortMenu> {
    private static final Identifier TEXTURE = MMCR.id("textures/gui/guitank.png");
    private static final Identifier AUTO_IO_TEXTURE = MMCR.id("textures/gui/guismartinterface.png");
    private static final Identifier BAR_TEXTURE = MMCR.id("textures/gui/guibar.png");
    private static final int GUI_TEXTURE_SIZE = 256;
    private static final int HEAT_X = 15;
    private static final int HEAT_Y = 10;
    private static final int HEAT_W = 20;
    private static final int HEAT_H = 61;
    private static final int TITLE_COLOR = -12566464;

    public HeatHatchScreen(HeatPortMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 166);
        titleLabelX += 32;
        titleLabelY += 3;
    }

    @Override protected BlockPos portPos() { return menu.pos(); }
    @Override protected IOType ownerIOType() { return menu.owner() == null ? null : menu.owner().ioType(); }
    @Override protected int portSlotCount() { return 0; }
    @Override protected Identifier texture(boolean autoIOPage) { return autoIOPage ? AUTO_IO_TEXTURE : TEXTURE; }

    static String displayLines(double heat, double capacity) {
        long safeHeat = safeWhole(heat);
        long safeCapacity = safeWhole(capacity);
        double temperature = safeCapacity <= 0L ? 0D : safeHeat / (double) safeCapacity;
        return "Heat: " + ReadableNumber.formatExact(safeHeat) + " J\n"
                + "Capacity: " + ReadableNumber.formatExact(safeCapacity) + " J/K\n"
                + "Temperature: " + String.format(Locale.ROOT, "%.1f K", temperature);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        clearTooltipEntries();
        if (autoIOPage) return;
        graphics.text(font, title, titleLabelX, titleLabelY, TITLE_COLOR, false);
        long heat = menu.heatAmount();
        long capacity = menu.heatCapacity();
        graphics.text(font, Component.translatable("gui.mmcr.heat.amount", ReadableNumber.format(heat),
                Component.translatable("mmcr.unit.heat")), titleLabelX, titleLabelY + 10, TITLE_COLOR, false);
        graphics.text(font, Component.translatable("gui.mmcr.heat.capacity", ReadableNumber.format(capacity),
                Component.translatable("mmcr.unit.heat_capacity")), titleLabelX, titleLabelY + 20, TITLE_COLOR, false);
        graphics.text(font, Component.translatable("gui.mmcr.heat.temperature",
                String.format(Locale.ROOT, "%.1f", menu.temperature()),
                Component.translatable("mmcr.unit.temperature")), titleLabelX, titleLabelY + 30, TITLE_COLOR, false);
        addTooltip(leftPos + titleLabelX, topPos + titleLabelY + 10, font.width(displayLines(heat, capacity)), 30,
                java.util.List.of(Component.literal(displayLines(heat, capacity))));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        super.extractBackground(graphics, mouseX, mouseY, partialTicks);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture(autoIOPage), leftPos, topPos, 0, 0,
                imageWidth, imageHeight, GUI_TEXTURE_SIZE, GUI_TEXTURE_SIZE);
        if (autoIOPage) return;
        int filled = FluidGuiRenderer.fillHeight(menu.heatAmount(), menu.heatCapacity(), HEAT_H);
        if (filled > 0) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, BAR_TEXTURE, leftPos + HEAT_X,
                    topPos + HEAT_Y + HEAT_H - filled, 196, HEAT_H - filled, HEAT_W, filled,
                    GUI_TEXTURE_SIZE, GUI_TEXTURE_SIZE);
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos + HEAT_X, topPos + HEAT_Y,
                176, 0, HEAT_W, HEAT_H, GUI_TEXTURE_SIZE, GUI_TEXTURE_SIZE);
    }

    private static long safeWhole(double value) {
        if (!Double.isFinite(value) || value <= 0D) return 0L;
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(value);
    }
}
