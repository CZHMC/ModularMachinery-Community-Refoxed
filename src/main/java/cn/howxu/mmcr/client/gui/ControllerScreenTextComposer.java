package cn.howxu.mmcr.client.gui;

import cn.howxu.mmcr.internal.runtime.ControllerScreenTextSnapshot;
import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * Composes controller text and lays it out for a client font.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class ControllerScreenTextComposer {
    public static final int DEFAULT_EXTERNAL_COLOR = 0xFFE8E8E8;

    private ControllerScreenTextComposer() {
    }

    public static List<ControllerTextLine> merge(List<ControllerTextLine> standard,
                                                  List<ControllerScreenTextSnapshot.Line> external) {
        List<ControllerTextLine> merged = new ArrayList<>(standard.size() + external.size());
        merged.addAll(standard);
        for (ControllerScreenTextSnapshot.Line line : external) {
            merged.add(new ControllerTextLine(line.text(), DEFAULT_EXTERNAL_COLOR));
        }
        return List.copyOf(merged);
    }

    public static List<VisualLine> wrap(Font font, List<ControllerTextLine> lines, int width) {
        List<VisualLine> wrapped = new ArrayList<>();
        for (ControllerTextLine line : lines) {
            boolean firstSegment = true;
            int lineWidth = Math.max(1, width - line.textXOffset());
            for (FormattedCharSequence visualLine : font.split(line.text(), lineWidth)) {
                wrapped.add(new VisualLine(visualLine, line.color(), line, firstSegment));
                firstSegment = false;
            }
        }
        return List.copyOf(wrapped);
    }

    /**
     * One client-font visual line produced from a logical controller line.
     *
     * @param text the wrapped visual text
     * @param color the render color inherited from the logical line
     * @param source the logical line that produced this visual line
     * @param firstSegment whether this is the first visual segment of the logical line
     * @author howxu <dev@howxu.cn>
     */
    public record VisualLine(FormattedCharSequence text, int color, ControllerTextLine source,
                             boolean firstSegment) {
        public int textXOffset() {
            return firstSegment ? source.textXOffset() : source.leftIndent();
        }
    }
}
