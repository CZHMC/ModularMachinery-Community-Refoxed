package cn.howxu.mmcr.compat.appliedenergistics2;

import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import org.jetbrains.annotations.Nullable;

/**
 * Isolates optional AE2 integration from the common runtime.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface AE2Bridge {
    static AE2Bridge get() {
        return AE2BridgeBootstrap.bridge();
    }

    boolean available();

    List<IOPortKind> portKinds();

    boolean isPort(String id);

    boolean openMenu(ServerPlayer player, Level level, BlockPos pos);

    default void onPortNeighborChanged(IOPortBlockEntity port) {
    }

    @Nullable
    default Identifier portOverlayTexture(IOPortKind kind) {
        return null;
    }

    default void registerCapabilities(RegisterCapabilitiesEvent event) {
    }

    default void registerJadeCommon(IWailaCommonRegistration registration) {
    }

    default void registerJadeClient(IWailaClientRegistration registration) {
    }
}
