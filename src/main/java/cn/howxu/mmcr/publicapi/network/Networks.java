package cn.howxu.mmcr.publicapi.network;

import cn.howxu.mmcr.internal.api.facade.network.NetworkAdapters;
import cn.howxu.mmcr.publicapi.behavior.MachineContext;
import net.minecraft.resources.Identifier;
import java.util.List;

/** Queued server network operations; dispatch timing and exceptions follow core.
 * @author howxu <dev@howxu.cn>
 */
public final class Networks {
    private Networks() {}
    public static List<NetworkPortView> interfaces(MachineContext context) { return NetworkAdapters.interfaces(context); }
    public static void sendRequest(NetworkPortView source, NodeView target, Identifier requestId, RequestPayload body) {
        NetworkAdapters.sendRequest(source, target, requestId, body);
    }
}
