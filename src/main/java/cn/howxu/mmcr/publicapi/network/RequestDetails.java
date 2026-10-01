package cn.howxu.mmcr.publicapi.network;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/** MMCR-provided request metadata. Normal peer is sender; failed peer is target.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface RequestDetails {
    Identifier requestId();
    NodeView peer();
}
