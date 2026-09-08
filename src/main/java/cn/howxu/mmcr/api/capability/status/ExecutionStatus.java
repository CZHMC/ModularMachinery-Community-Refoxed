package cn.howxu.mmcr.api.capability.status;

import cn.howxu.mmcr.MMCR;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Describes the status of a capability operation.
 *
 * @param id the status identifier
 * @param severity the status severity
 * @param source the source that produced the status
 * @param details additional immutable status details
 * @author howxu <dev@howxu.cn>
 */
public record ExecutionStatus(
        Identifier id,
        StatusSeverity severity,
        Identifier source,
        Map<String, String> details) {
    public ExecutionStatus {
        details = Map.copyOf(details);
    }

    /**
     * Resolves the optional reason detail through the registered failure reasons.
     *
     * @return the registered reason, or {@code null} when the detail is absent or unknown
     */
    public FailureReason reason() {
        String value = details.get("reason");
        if (value == null || value.isBlank()) return null;
        try {
            Identifier id = value.contains(":") ? Identifier.parse(value) : MMCR.id(value);
            return FailureReasonRegistry.find(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
