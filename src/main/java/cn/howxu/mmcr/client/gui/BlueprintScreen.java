package cn.howxu.mmcr.client.gui;

import cn.howxu.mmcr.api.machine.Machine;
import cn.howxu.mmcr.client.preview.StructureMaterialSummary.Entry;
import cn.howxu.mmcr.client.preview.StructurePreviewPanel;
import cn.howxu.mmcr.client.preview.StructurePreviewSchema.Candidate;
import cn.howxu.mmcr.internal.network.PktBlueprintStageUpdatePayload;
import cn.howxu.mmcr.registry.ModDataComponents;
import cn.howxu.mmcr.util.ReadableNumber;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Objects;

/** Client-side host for the standalone blueprint structure preview.
 * @author howxu <dev@howxu.cn>
 */
public final class BlueprintScreen extends Screen {
    private static final int BASE_WIDTH = 420;
    private static final int BASE_HEIGHT = 260;
    private static final int OUTER_MARGIN = 8;
    private static final int GAP = 6;
    private static final int ITEM_ROW_GAP = 2;
    private static final int ITEM_ROW_SLOT_X = OUTER_MARGIN;
    private static final int SLOT_COUNT = 12;
    private static final float MATERIAL_QUANTITY_SCALE = 0.6F;
    private static final int PREVIEW_HEIGHT = 190;
    private static final int SLOT_SIZE = 24;
    private static final int SLOT_ICON_OFFSET = 4;
    private static final int BUTTON_HEIGHT = 18;
    private static final int TEXT_COLOR = 0xFF202020;
    private static final int PANEL_COLOR = 0xFFB6E4F2;
    private static final int OUTER_BORDER_COLOR = 0xFF40798B;
    private static final int INNER_BORDER_COLOR = 0xFFDDF4FA;

    private final Machine machine;
    private final ItemStack blueprint;
    private final StructurePreviewPanel panel;
    private @Nullable BlueprintLayout layout;
    private @Nullable Button previousLayerButton;
    private @Nullable Button nextLayerButton;
    private @Nullable Button allLayersButton;
    private @Nullable Button resetButton;
    private @Nullable Button nextStageButton;
    private @Nullable Button previousStageButton;
    private boolean closed;

    public BlueprintScreen(Machine machine, ItemStack blueprint) {
        super(Component.translatable("gui.mmcr.blueprint.title", machine.displayName()));
        this.machine = Objects.requireNonNull(machine, "machine");
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        this.panel = new StructurePreviewPanel(machine,
                blueprint.getOrDefault(ModDataComponents.BLUEPRINT_STAGE.get(), 0));
    }

    @Override
    protected void init() {
        super.init();
        BlueprintLayout currentLayout = layoutFor(width, height, panel.hasMultipleStages());
        layout = currentLayout;
        previousLayerButton = addRenderableWidget(symbolButton("+", currentLayout.layerButtons()[0],
                "jei.mmcr.structure_preview.previous_layer", panel::selectPreviousLayer));
        nextLayerButton = addRenderableWidget(symbolButton("-", currentLayout.layerButtons()[1],
                "jei.mmcr.structure_preview.next_layer", panel::selectNextLayer));
        allLayersButton = addRenderableWidget(symbolButton("A", currentLayout.layerButtons()[2],
                "jei.mmcr.structure_preview.all_layers", panel::showAllLayers));
        resetButton = addRenderableWidget(symbolButton("R", currentLayout.layerButtons()[3],
                "jei.mmcr.structure_preview.reset", panel::reset));
        if (currentLayout.nextStageButton() != null) {
            nextStageButton = addRenderableWidget(textButton(
                    "gui.mmcr.blueprint.next_level", currentLayout.nextStageButton(),
                    "jei.mmcr.structure_preview.next_level", this::selectNextStage));
            previousStageButton = addRenderableWidget(textButton(
                    "gui.mmcr.blueprint.previous_level", currentLayout.previousStageButton(),
                    "jei.mmcr.structure_preview.previous_level", this::selectPreviousStage));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        BlueprintLayout currentLayout = layout;
        if (currentLayout == null) return;

        drawPanel(graphics, currentLayout);
        BlueprintRect preview = currentLayout.preview();
        graphics.fill(preview.x(), preview.y(), preview.x() + preview.width(), preview.y() + preview.height(), 0xFF000000);
        graphics.enableScissor(preview.x(), preview.y(), preview.x() + preview.width(), preview.y() + preview.height());
        try {
            panel.render(graphics, preview.width(), preview.height(), partialTicks,
                    preview.x(), preview.y(), preview.x(), preview.y());
        } finally {
            graphics.disableScissor();
        }
        graphics.nextStratum();
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
        renderLabels(graphics, currentLayout);
        renderItems(graphics, currentLayout, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        BlueprintLayout currentLayout = layout;
        if (currentLayout != null && itemRowAt(currentLayout, event.x(), event.y())) return true;
        if (currentLayout != null && currentLayout.preview().contains(event.x(), event.y())) {
            return panel.mouseClicked(previewX(event.x()), previewY(event.y()), event.button());
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        BlueprintLayout currentLayout = layout;
        if (currentLayout != null && itemRowAt(currentLayout, event.x(), event.y())) return true;
        if (currentLayout != null && currentLayout.preview().contains(event.x(), event.y())) {
            return panel.mouseReleased(previewX(event.x()), previewY(event.y()), event.button());
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        BlueprintLayout currentLayout = layout;
        if (currentLayout != null && itemRowAt(currentLayout, event.x(), event.y())) return true;
        if (currentLayout != null && currentLayout.preview().contains(event.x(), event.y())) {
            float scale = currentLayout.scale();
            return panel.mouseDragged(previewX(event.x()), previewY(event.y()), event.button(), dragX / scale, dragY / scale);
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        BlueprintLayout currentLayout = layout;
        if (currentLayout != null && itemRowAt(currentLayout, mouseX, mouseY)) return true;
        if (currentLayout != null && currentLayout.preview().contains(mouseX, mouseY)) {
            return panel.mouseScrolled(previewX(mouseX), previewY(mouseY), scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void removed() {
        if (closed) return;
        closed = true;
        panel.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static BlueprintLayout layoutFor(int screenWidth, int screenHeight, boolean multipleStages) {
        float scale = Math.min(1.0F, Math.min((screenWidth - 2.0F * OUTER_MARGIN) / BASE_WIDTH,
                (screenHeight - 2.0F * OUTER_MARGIN) / BASE_HEIGHT));
        int left = Math.round((screenWidth - BASE_WIDTH * scale) / 2.0F);
        int top = Math.round((screenHeight - BASE_HEIGHT * scale) / 2.0F);

        int contentWidth = BASE_WIDTH - 2 * OUTER_MARGIN;
        int leftWidth = Math.round((contentWidth - GAP) * 0.70F);
        int rightWidth = contentWidth - GAP - leftWidth;
        int rightX = OUTER_MARGIN + leftWidth + GAP;
        int materialsY = OUTER_MARGIN + PREVIEW_HEIGHT + ITEM_ROW_GAP;
        int candidatesY = materialsY + SLOT_SIZE + ITEM_ROW_GAP;
        int buttonWidth = (rightWidth - GAP) / 2;
        int controlsY = 94;
        BlueprintRect[] layerButtons = new BlueprintRect[]{
                scaledRect(left, top, rightX, controlsY, buttonWidth, BUTTON_HEIGHT, scale),
                scaledRect(left, top, rightX + buttonWidth + GAP, controlsY, buttonWidth, BUTTON_HEIGHT, scale),
                scaledRect(left, top, rightX, controlsY + BUTTON_HEIGHT + GAP, buttonWidth, BUTTON_HEIGHT, scale),
                scaledRect(left, top, rightX + buttonWidth + GAP, controlsY + BUTTON_HEIGHT + GAP,
                        buttonWidth, BUTTON_HEIGHT, scale)
        };
        BlueprintRect nextStage = multipleStages
                ? scaledRect(left, top, rightX, controlsY + 2 * (BUTTON_HEIGHT + GAP),
                        buttonWidth * 2 + GAP, BUTTON_HEIGHT, scale)
                : null;
        BlueprintRect previousStage = multipleStages
                ? scaledRect(left, top, rightX, controlsY + 3 * (BUTTON_HEIGHT + GAP),
                        buttonWidth * 2 + GAP, BUTTON_HEIGHT, scale)
                : null;
        return new BlueprintLayout(left, top, scale,
                scaledRect(left, top, OUTER_MARGIN, OUTER_MARGIN, leftWidth, PREVIEW_HEIGHT, scale),
                scaledRect(left, top, OUTER_MARGIN, candidatesY, leftWidth, SLOT_SIZE, scale),
                scaledRect(left, top, OUTER_MARGIN, materialsY, leftWidth, SLOT_SIZE, scale),
                scaledRect(left, top, rightX, OUTER_MARGIN, rightWidth, 78, scale),
                layerButtons, nextStage, previousStage);
    }

    static double localMouse(double mouse, int origin, float scale) {
        return (mouse - origin) / scale;
    }

    static double previewMouse(double mouse, int origin) {
        return mouse - origin;
    }

    private Button symbolButton(String symbol, BlueprintRect rect, String tooltipKey, Runnable action) {
        Button button = Button.builder(Component.literal(symbol), clicked -> action.run())
                .bounds(rect.x(), rect.y(), rect.width(), rect.height()).build();
        button.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        return button;
    }

    private Button textButton(String key, BlueprintRect rect, String tooltipKey, Runnable action) {
        Button button = Button.builder(Component.translatable(key), clicked -> action.run())
                .bounds(rect.x(), rect.y(), rect.width(), rect.height()).build();
        button.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
        return button;
    }

    private void selectNextStage() {
        if (!panel.isReady()) return;
        panel.selectNextStage();
        saveStage();
    }

    private void selectPreviousStage() {
        if (!panel.isReady()) return;
        panel.selectPreviousStage();
        saveStage();
    }

    private void saveStage() {
        int stageNumber = panel.stageNumber();
        blueprint.set(ModDataComponents.BLUEPRINT_STAGE.get(), stageNumber);
        ClientPacketDistributor.sendToServer(new PktBlueprintStageUpdatePayload(stageNumber));
    }

    private void drawPanel(GuiGraphicsExtractor graphics, BlueprintLayout currentLayout) {
        int left = currentLayout.left();
        int top = currentLayout.top();
        int right = left + Math.round(BASE_WIDTH * currentLayout.scale());
        int bottom = top + Math.round(BASE_HEIGHT * currentLayout.scale());
        graphics.fill(left, top, right, bottom, PANEL_COLOR);
        graphics.fill(left, top, right, top + 1, OUTER_BORDER_COLOR);
        graphics.fill(left, bottom - 1, right, bottom, OUTER_BORDER_COLOR);
        graphics.fill(left, top, left + 1, bottom, OUTER_BORDER_COLOR);
        graphics.fill(right - 1, top, right, bottom, OUTER_BORDER_COLOR);
        graphics.fill(left + 1, top + 1, right - 1, top + 2, INNER_BORDER_COLOR);
        graphics.fill(left + 1, bottom - 2, right - 1, bottom - 1, INNER_BORDER_COLOR);
        graphics.fill(left + 1, top + 1, left + 2, bottom - 1, INNER_BORDER_COLOR);
        graphics.fill(right - 2, top + 1, right - 1, bottom - 1, INNER_BORDER_COLOR);
    }

    private void renderLabels(GuiGraphicsExtractor graphics, BlueprintLayout currentLayout) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(currentLayout.left(), currentLayout.top());
        graphics.pose().scale(currentLayout.scale(), currentLayout.scale());
        int infoX = (int) Math.round(localMouse(currentLayout.info().x(), currentLayout.left(), currentLayout.scale()));
        int infoY = (int) Math.round(localMouse(currentLayout.info().y(), currentLayout.top(), currentLayout.scale()));
        graphics.text(font, title, infoX + 4, infoY + 4, TEXT_COLOR, false);
        int selectedLayer = panel.selectedLayer();
        Component layer = selectedLayer < 0
                ? Component.translatable("jei.mmcr.structure_preview.all_layers")
                : Component.literal(Integer.toString(selectedLayer));
        graphics.text(font, Component.translatable("gui.mmcr.blueprint.y", layer), infoX + 4, infoY + 24,
                TEXT_COLOR, false);
        if (panel.hasMultipleStages()) {
            graphics.text(font, Component.translatable("gui.mmcr.blueprint.level", panel.stageNumber()),
                    infoX + 4, infoY + 40, TEXT_COLOR, false);
        }
        graphics.pose().popMatrix();
    }

    private void renderItems(GuiGraphicsExtractor graphics, BlueprintLayout currentLayout, int mouseX, int mouseY) {
        long timeMillis = System.currentTimeMillis();
        graphics.pose().pushMatrix();
        graphics.pose().translate(currentLayout.left(), currentLayout.top());
        graphics.pose().scale(currentLayout.scale(), currentLayout.scale());
        int candidatesY = (int) Math.round(localMouse(currentLayout.candidates().y(), currentLayout.top(), currentLayout.scale()));
        int materialsY = (int) Math.round(localMouse(currentLayout.materials().y(), currentLayout.top(), currentLayout.scale()));
        int visibleSlotCount = visibleSlotCount();
        for (int slot = 0; slot < visibleSlotCount; slot++) {
            BlueprintRect candidateSlot = slotRect(currentLayout, currentLayout.candidates(), slot);
            Candidate candidate = panel.candidateAt(slot, timeMillis, visibleSlotCount);
            if (candidate != null) {
                ItemStack stack = candidate.stack();
                int x = ITEM_ROW_SLOT_X + slot * SLOT_SIZE + SLOT_ICON_OFFSET;
                int y = candidatesY + SLOT_ICON_OFFSET;
                graphics.item(stack, x, y, slot);
                graphics.itemDecorations(font, stack, x, y);
                if (candidateSlot.contains(mouseX, mouseY)) {
                    ArrayList<Component> tooltip = new ArrayList<>(getTooltipFromItem(minecraft, stack));
                    if (candidate.modifier()) tooltip.add(Component.translatable("gui.mmcr.blueprint.modifier"));
                    graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
                }
            }

            BlueprintRect materialSlot = slotRect(currentLayout, currentLayout.materials(), slot);
            Entry entry = panel.materialAt(slot, timeMillis, visibleSlotCount);
            if (entry != null) {
                ItemStack stack = entry.stack().copyWithCount(1);
                int x = ITEM_ROW_SLOT_X + slot * SLOT_SIZE + SLOT_ICON_OFFSET;
                int y = materialsY + SLOT_ICON_OFFSET;
                graphics.item(stack, x, y, slot);
                String quantity = entry.count() > 1
                        ? ReadableNumber.formatForSlot(entry.count(), 0, "") : null;
                graphics.pose().pushMatrix();
                graphics.pose().translate(x + 8, y + 8);
                graphics.pose().scale(MATERIAL_QUANTITY_SCALE, MATERIAL_QUANTITY_SCALE);
                graphics.itemDecorations(font, stack, 0, 0, quantity);
                graphics.pose().popMatrix();
                if (materialSlot.contains(mouseX, mouseY)) {
                    ArrayList<Component> tooltip = new ArrayList<>(getTooltipFromItem(minecraft, stack));
                    tooltip.add(Component.translatable("jei.mmcr.machine_recipe.item_count",
                            ReadableNumber.formatExact(entry.count())));
                    graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
                }
            }
        }
        graphics.pose().popMatrix();
    }

    private int visibleSlotCount() {
        return SLOT_COUNT;
    }

    private boolean itemRowAt(BlueprintLayout currentLayout, double mouseX, double mouseY) {
        return currentLayout.candidates().contains(mouseX, mouseY) || currentLayout.materials().contains(mouseX, mouseY);
    }

    private BlueprintRect slotRect(BlueprintLayout currentLayout, BlueprintRect row, int slot) {
        int baseX = ITEM_ROW_SLOT_X + slot * SLOT_SIZE;
        int baseY = (int) Math.round(localMouse(row.y(), currentLayout.top(), currentLayout.scale()));
        return scaledRect(currentLayout.left(), currentLayout.top(), baseX, baseY, SLOT_SIZE, SLOT_SIZE, currentLayout.scale());
    }

    private double previewX(double mouseX) {
        BlueprintLayout currentLayout = Objects.requireNonNull(layout, "screen not initialized");
        return previewMouse(mouseX, currentLayout.preview().x());
    }

    private double previewY(double mouseY) {
        BlueprintLayout currentLayout = Objects.requireNonNull(layout, "screen not initialized");
        return previewMouse(mouseY, currentLayout.preview().y());
    }

    private static BlueprintRect scaledRect(int left, int top, int x, int y, int width, int height, float scale) {
        int scaledX = Math.round(left + x * scale);
        int scaledY = Math.round(top + y * scale);
        int scaledRight = Math.round(left + (x + width) * scale);
        int scaledBottom = Math.round(top + (y + height) * scale);
        return new BlueprintRect(scaledX, scaledY, Math.max(1, scaledRight - scaledX), Math.max(1, scaledBottom - scaledY));
    }
}

record BlueprintRect(int x, int y, int width, int height) {
    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}

record BlueprintLayout(int left, int top, float scale, BlueprintRect preview,
        BlueprintRect candidates, BlueprintRect materials, BlueprintRect info,
        BlueprintRect[] layerButtons, @Nullable BlueprintRect nextStageButton,
        @Nullable BlueprintRect previousStageButton) {
}
