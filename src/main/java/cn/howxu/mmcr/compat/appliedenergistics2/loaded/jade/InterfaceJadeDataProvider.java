package cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade;

import appeng.api.networking.IGridNode;
import appeng.me.helpers.IGridConnectedBlockEntity;
import cn.howxu.mmcr.MMCR;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Synchronizes the AE2 grid-node state for the MMCR-hosted interface.
 *
 * @author howxu <dev@howxu.cn>
 */
public enum InterfaceJadeDataProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    static final Identifier UID = MMCR.id("ae2_input_interface_grid");
    static final String STATE = "gridNodeState";

    @Override
    public Identifier getUid() {
        return UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getTarget() instanceof IGridConnectedBlockEntity host)) return;
        data.putByte(STATE, (byte) state(host.getActionableNode()));
    }

    private static int state(IGridNode node) {
        if (node == null || !node.isPowered()) return 0;
        if (!node.hasGridBooted()) return 1;
        return node.meetsChannelRequirements() ? 3 : 2;
    }
}
