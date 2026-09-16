package cn.howxu.mmcr.compat.appliedenergistics2.extendedae;

import cn.howxu.mmcr.internal.port.IOPortKind;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;
import snownee.jade.api.IWailaCommonRegistration;

/**
 * Isolates optional ExtendedAE integration from the AE2 bridge.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface ExtendedAEContributor {
    boolean available();

    List<IOPortKind> portKinds();

    boolean isPort(String id);

    boolean openMenu(ServerPlayer player, Level level, BlockPos pos);

    @Nullable
    Identifier portOverlayTexture(IOPortKind kind);

    void registerCapabilities(RegisterCapabilitiesEvent event);

    void registerJadeCommon(IWailaCommonRegistration registration);
}
