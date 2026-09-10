package cn.howxu.mmcr.compat.jade;

import cn.howxu.mmcr.MMCR;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * @author howxu <dev@howxu.cn>
 */
public enum AE2InputInterfaceComponentProvider implements IComponentProvider<BlockAccessor> {
    INSTANCE;

    static final Identifier UID = MMCR.id("ae2_input_interface");

    @Override
    public Identifier getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag data = accessor.getServerData();
        boolean connected = data.getBooleanOr("connected", false);
        boolean powered = data.getBooleanOr("powered", false);
        boolean active = data.getBooleanOr("active", false);
        Component status = connected
                ? Component.translatable(active ? "jade.mmcr.ae2_input_interface.active" : "jade.mmcr.ae2_input_interface.online")
                        .withStyle(active ? ChatFormatting.GREEN : ChatFormatting.YELLOW)
                : Component.translatable("jade.mmcr.ae2_input_interface.offline")
                        .withStyle(ChatFormatting.RED);
        tooltip.add(status);
        tooltip.add(Component.translatable("jade.mmcr.ae2_input_interface.powered",
                powered
                        ? Component.translatable("jade.mmcr.ae2_input_interface.yes").withStyle(ChatFormatting.GREEN)
                        : Component.translatable("jade.mmcr.ae2_input_interface.no").withStyle(ChatFormatting.RED)));
    }
}