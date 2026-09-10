package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;

import java.util.Optional;

/**
 * Converts one MMCR resource family to and from its AE2 key representation.
 *
 * @param <R> resource type handled by this adapter
 * @author howxu <dev@howxu.cn>
 */
public interface AE2KeyAdapter<R> {
    AEKeyType keyType();

    Class<R> resourceType();

    AEKey toKey(R resource);

    Optional<R> toResource(AEKey key);
}
