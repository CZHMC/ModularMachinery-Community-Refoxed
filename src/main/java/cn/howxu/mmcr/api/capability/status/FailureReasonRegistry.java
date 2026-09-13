package cn.howxu.mmcr.api.capability.status;

import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry for stable execution failure reasons.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class FailureReasonRegistry {
    private static final Map<Identifier, FailureReason> REASONS = new LinkedHashMap<>();
    private static boolean frozen;

    private FailureReasonRegistry() {
    }

    public static synchronized void register(FailureReason reason) {
        if (reason == null) throw new IllegalArgumentException("reason must not be null");
        if (frozen) throw new IllegalStateException("Failure reason registry is frozen");
        if (REASONS.putIfAbsent(reason.id(), reason) != null) {
            throw new IllegalArgumentException("Duplicate failure reason: " + reason.id());
        }
    }

    public static synchronized FailureReason find(Identifier id) {
        return id == null ? null : REASONS.get(id);
    }

    public static synchronized FailureReason resolve(Identifier id) {
        FailureReason reason = find(id);
        return reason == null ? BuiltinFailureReasons.UNKNOWN : reason;
    }

    public static synchronized void freeze() {
        frozen = true;
    }

    public static synchronized boolean isFrozen() {
        return frozen;
    }

    public static synchronized void clearForTesting() {
        REASONS.clear();
        frozen = false;
    }
}
