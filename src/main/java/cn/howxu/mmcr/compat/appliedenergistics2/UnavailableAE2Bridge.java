package cn.howxu.mmcr.compat.appliedenergistics2;

import cn.howxu.mmcr.internal.port.IOPortKind;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Inert bridge used when AE2 is unavailable.
 *
 * @author howxu <dev@howxu.cn>
 */
final class UnavailableAE2Bridge implements AE2Bridge {
    static final UnavailableAE2Bridge INSTANCE = new UnavailableAE2Bridge();

    private UnavailableAE2Bridge() {
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
}
