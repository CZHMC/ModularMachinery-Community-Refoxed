package cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind;

import appeng.api.networking.IManagedGridNode;
import appeng.helpers.InterfaceLogic;
import appeng.helpers.InterfaceLogicHost;
import cn.howxu.mmcr.internal.port.IOPortKind;
import net.minecraft.world.item.Item;

/**
 * A port kind that supplies its AE2 interface logic profile.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface InterfaceLogicKind extends IOPortKind {
    default InterfaceLogic createInterfaceLogic(IManagedGridNode node, InterfaceLogicHost host, Item icon) {
        return new InterfaceLogic(node, host, icon);
    }
}
