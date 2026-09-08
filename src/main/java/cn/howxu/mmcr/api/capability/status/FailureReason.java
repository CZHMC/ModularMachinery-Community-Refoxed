package cn.howxu.mmcr.api.capability.status;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Registered, translatable explanation for an execution failure.
 *
 * @param id the stable failure reason identifier
 * @param translationKey the client translation key
 * @author howxu <dev@howxu.cn>
 */
public record FailureReason(Identifier id, String translationKey) {
    public FailureReason {
        Objects.requireNonNull(id, "id");
        if (translationKey == null || translationKey.isBlank()) throw new IllegalArgumentException("translationKey");
    }
}
