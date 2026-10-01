package cn.howxu.mmcr.publicapi.network;

import cn.howxu.mmcr.internal.api.facade.network.NetworkAdapters;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/** Stable MMCR-produced machine identity, not a live controller handle.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface NodeView {
    static NodeView of(Identifier type, long hash) { return NetworkAdapters.node(type, hash); }
    Identifier type();
    long hash();
}
