package cn.howxu.mmcr.compat.extendedae;

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
 * Inert contributor used when ExtendedAE is unavailable.
 *
 * @author howxu <dev@howxu.cn>
 */
final class UnavailableExtendedAEContributor implements ExtendedAEContributor {
    static final UnavailableExtendedAEContributor INSTANCE = new UnavailableExtendedAEContributor();

    private UnavailableExtendedAEContributor() {
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<IOPortKind> portKinds() {
        return List.of();
    }

    @Override
    public boolean isPort(String id) {
        return false;
    }

    @Override
    public boolean openMenu(ServerPlayer player, Level level, BlockPos pos) {
        return false;
    }

    @Override
    public @Nullable Identifier portOverlayTexture(IOPortKind kind) {
        return null;
    }

    @Override
    public void registerCapabilities(RegisterCapabilitiesEvent event) {
    }

    @Override
    public void registerJadeCommon(IWailaCommonRegistration registration) {
    }
}
