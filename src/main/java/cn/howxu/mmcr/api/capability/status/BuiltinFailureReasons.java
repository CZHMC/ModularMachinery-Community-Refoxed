package cn.howxu.mmcr.api.capability.status;

import net.minecraft.resources.Identifier;

/**
 * Failure reasons supplied by the core status model.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class BuiltinFailureReasons {
    public static final FailureReason UNKNOWN = new FailureReason(
            Identifier.fromNamespaceAndPath("mmcr", "unknown"),
            "gui.mmcr.failure.unknown",
            Integer.MIN_VALUE);

    private BuiltinFailureReasons() {
    }
}
