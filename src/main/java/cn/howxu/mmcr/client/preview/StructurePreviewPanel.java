package cn.howxu.mmcr.client.preview;

import cn.howxu.mmcr.api.machine.Machine;
import cn.howxu.mmcr.api.machine.MachineStructureStage;
import cn.howxu.mmcr.client.preview.StructureMaterialSummary.Entry;
import cn.howxu.mmcr.client.preview.StructurePreviewSchema.Candidate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * JEI-independent structure preview panel shared by preview hosts.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class StructurePreviewPanel implements AutoCloseable {
    public static final int VISIBLE_SLOT_COUNT = 8;

    private final Machine machine;
    private final List<MachineStructureStage> stages;
    private final StructurePreviewCompilation compilation;
    private @Nullable StructurePreviewSchema schema;
    private @Nullable StructurePreviewWidget widget;
    private StructureMaterialSummary materials = StructureMaterialSummary.empty();
    private int stageIndex;
    private long compileAnimationStart = -1L;
    private boolean closed;

    public StructurePreviewPanel(Machine machine) {
        this.machine = Objects.requireNonNull(machine, "machine");
        this.stages = List.copyOf(machine.structureStages());
        if (stages.isEmpty()) throw new IllegalArgumentException("machine structure stages empty");
        this.compilation = StructurePreviewCompilationCache.instance().acquire(machine);
    }

    public void render(GuiGraphicsExtractor graphics, int width, int height,
            float partialTick, int guiOriginX, int guiOriginY) {
        if (closed) return;
        ensurePreviewStarted();
        if (widget == null) {
            if (compilation.failure() != null) {
                graphics.text(Minecraft.getInstance().font,
                        Component.translatable("jei.mmcr.structure_preview.unavailable"),
                        0, height / 2, 0xFFFFFFFF, false);
            } else {
                if (compileAnimationStart < 0L) compileAnimationStart = System.currentTimeMillis();
                long elapsed = Math.max(0L, System.currentTimeMillis() - compileAnimationStart);
                graphics.text(Minecraft.getInstance().font, Component.literal(compileStatus(elapsed)),
                        0, height / 2, 0xFFFFFFFF, false);
            }
            return;
        }

        widget.render(graphics, 0, 0, width, height, partialTick, guiOriginX, guiOriginY);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return widget != null && widget.mouseClicked(mouseX, mouseY, button);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return widget != null && widget.mouseReleased(mouseX, mouseY, button);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button,
            double dragX, double dragY) {
        return widget != null && widget.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        return widget != null && widget.mouseScrolled(mouseX, mouseY, scrollDelta);
    }

    public boolean isReady() {
        return widget != null;
    }

    public @Nullable StructurePreviewSchema schema() {
        return schema;
    }

    public int selectedLayer() {
        return widget == null ? -1 : widget.selectedLayer();
    }

    public @Nullable BlockPos selectedPosition() {
        return widget == null ? null : widget.selectedPosition();
    }

    public List<StructurePreviewSchema.Candidate> selectedCandidates() {
        if (schema == null || selectedPosition() == null) return List.of();
        BlockPos position = selectedPosition();
        List<StructurePreviewSchema.Candidate> candidates = schema.previewCandidatesAt(position);
        if (!candidates.isEmpty()) return candidates;
        BlockState state = schema.stateAt(position);
        if (state == null || state.getBlock().asItem() == Items.AIR) return List.of();
        return List.of(new StructurePreviewSchema.Candidate(new ItemStack(state.getBlock()), false));
    }

    public @Nullable Candidate candidateAt(int slot, long timeMillis) {
        List<StructurePreviewSchema.Candidate> candidates = selectedCandidates();
        int index = candidateIndex(slot, timeMillis, candidates.size());
        return index < 0 ? null : candidates.get(index);
    }

    public StructureMaterialSummary materials() {
        return materials;
    }

    public @Nullable Entry materialAt(int slot, long timeMillis) {
        if (slot < 0 || slot >= VISIBLE_SLOT_COUNT) return null;
        List<StructureMaterialSummary.Entry> entries = materials.entries();
        int first = materialPage(timeMillis, entries.size()) * VISIBLE_SLOT_COUNT;
        int index = first + slot;
        return index < entries.size() ? entries.get(index) : null;
    }

    public boolean hasMultipleStages() {
        return stages.size() > 1;
    }

    public int stageNumber() {
        return stages.get(stageIndex).number();
    }

    public void selectPreviousLayer() {
        if (widget != null) widget.selectPreviousLayer();
    }

    public void selectNextLayer() {
        if (widget != null) widget.selectNextLayer();
    }

    public void showAllLayers() {
        if (widget != null) widget.showAllLayers();
    }

    public void reset() {
        if (widget != null) widget.reset();
    }

    public void selectNextStage() {
        if (closed || widget == null || stages.size() <= 1) return;
        widget.close();
        stageIndex = stageIndexAfter(stageIndex, stages.size());
        schema = new StructurePreviewSchemaFactory().create(stages.get(stageIndex), machine.registryName());
        widget = new StructurePreviewWidget(new StructurePreviewRenderer(schema));
        materials = StructureMaterialSummary.from(schema);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (widget != null) widget.close();
    }

    static int candidateIndex(int slot, long timeMillis, int candidateCount) {
        if (slot < 0 || slot >= VISIBLE_SLOT_COUNT || candidateCount <= 0) return -1;
        int offset = (int) (Math.floorDiv(timeMillis, 1_000L) % candidateCount);
        return Math.floorMod(slot + offset, candidateCount);
    }

    static int materialPage(long timeMillis, int materialCount) {
        int pageCount = (materialCount + VISIBLE_SLOT_COUNT - 1) / VISIBLE_SLOT_COUNT;
        if (pageCount == 0) return 0;
        return Math.floorMod((int) (Math.floorDiv(timeMillis, 8_000L) % pageCount), pageCount);
    }

    static int stageIndexAfter(int currentIndex, int stageCount) {
        return stageCount <= 1 ? currentIndex : Math.floorMod(currentIndex + 1, stageCount);
    }

    private void ensurePreviewStarted() {
        compilation.start();
        StructurePreviewSchema completed = compilation.schema();
        if (completed != null && widget == null) {
            schema = completed;
            materials = StructureMaterialSummary.from(completed);
            widget = new StructurePreviewWidget(new StructurePreviewRenderer(completed));
        }
    }

    private static String compileStatus(long elapsedMillis) {
        int dotCount = (int) (Math.max(0L, elapsedMillis) / 1_000L % 3L) + 1;
        return "Render compile" + ".".repeat(dotCount);
    }
}
