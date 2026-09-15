package cn.howxu.mmcr.api.capability.async;

import java.util.Objects;

/**
 * A worker-safe resource action without a live storage location.
 *
 * @param resource resource value
 * @param amount resource amount
 * @param insert whether the resource is inserted rather than extracted
 * @author howxu <dev@howxu.cn>
 */
public record AsyncResourceAction(AsyncResourceValue resource, long amount, boolean insert) {
    public AsyncResourceAction {
        Objects.requireNonNull(resource, "resource");
        if (amount <= 0L) throw new IllegalArgumentException("amount must be positive");
    }
}
