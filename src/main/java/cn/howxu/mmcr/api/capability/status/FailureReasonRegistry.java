package cn.howxu.mmcr.api.capability.status;

import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for stable execution failure reasons.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class FailureReasonRegistry {
    private static final Map<Identifier, FailureReason> REASONS = new ConcurrentHashMap<>();

    private FailureReasonRegistry() {
    }

    public static void register(FailureReason reason) {
        if (reason == null) throw new IllegalArgumentException("reason must not be null");
        if (REASONS.putIfAbsent(reason.id(), reason) != null) {
            throw new IllegalArgumentException("Duplicate failure reason: " + reason.id());
        }
    }

    public static FailureReason find(Identifier id) {
        return id == null ? null : REASONS.get(id);
    }
}
