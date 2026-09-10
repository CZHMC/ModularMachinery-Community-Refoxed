package cn.howxu.mmcr.compat.jade;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2InputInterfaceBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * @author howxu <dev@howxu.cn>
 */
public enum AE2InputInterfaceDataProvider implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    @Override
    public Identifier getUid() {
        return AE2InputInterfaceComponentProvider.UID;
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getTarget() instanceof AE2InputInterfaceBlockEntity host)) return;
        boolean hasGrid = host.getMainNode().hasGridBooted();
        boolean powered = host.getMainNode().isPowered();
        boolean active = host.getMainNode().isActive();
        data.putBoolean("connected", hasGrid);
        data.putBoolean("powered", powered);
        data.putBoolean("active", active);
    }
}