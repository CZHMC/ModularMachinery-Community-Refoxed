package cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2InputInterfaceBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.TooltipPosition;

/**
 * Renders the AE2-native grid-node state in Jade.
 *
 * @author howxu <dev@howxu.cn>
 */
public enum AE2InputInterfaceJadeComponentProvider implements IComponentProvider<BlockAccessor> {
    INSTANCE;

    @Override
    public Identifier getUid() {
        return AE2InputInterfaceJadeDataProvider.UID;
    }

    @Override
    public int getDefaultPriority() {
        return TooltipPosition.TAIL - 9;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!(accessor.getTarget() instanceof AE2InputInterfaceBlockEntity)) return;
        int state = accessor.getServerData().getByteOr(AE2InputInterfaceJadeDataProvider.STATE, (byte) 0);
        String key = switch (state) {
            case 1 -> "waila.ae2.NetworkBooting";
            case 2 -> "waila.ae2.DeviceMissingChannel";
            case 3 -> "waila.ae2.DeviceOnline";
            default -> "waila.ae2.DeviceOffline";
        };
        ChatFormatting color = switch (state) {
            case 1, 2 -> ChatFormatting.YELLOW;
            case 3 -> ChatFormatting.GREEN;
            default -> ChatFormatting.RED;
        };
        tooltip.add(Component.translatable(key)); // .withStyle(color)
    }
}
