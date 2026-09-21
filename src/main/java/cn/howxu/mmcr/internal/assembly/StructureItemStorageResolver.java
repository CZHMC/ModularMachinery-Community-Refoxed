package cn.howxu.mmcr.internal.assembly;

import cn.howxu.mmcr.compat.appliedenergistics2.AE2Bridge;
import cn.howxu.mmcr.internal.item.TerminalData;
import cn.howxu.mmcr.internal.item.TerminalInventoryMode;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Resolves terminal storage selections to server-side structure item storage.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class StructureItemStorageResolver {
    private StructureItemStorageResolver() {}

    public static Optional<StructureItemStorage> resolve(ServerPlayer player, TerminalData data) {
        switch (data.inventoryMode()) {
            case INVENTORY -> {
                return Optional.of(new PlayerInventoryStructureItemStorage(player));
            }
            case AE2 -> {
                GlobalPos accessPoint = data.ae2AccessPoint();
                return accessPoint == null ? Optional.empty()
                        : AE2Bridge.get().resolveTerminalNetworkStorage(player, accessPoint);
            }
            case CONTAINER -> {
                GlobalPos container = data.container();
                if (container == null || AE2Bridge.get().isWirelessAccessPoint(player, container)) return Optional.empty();
                ServerLevel level = player.level().getServer().getLevel(container.dimension());
                if (level == null || !level.hasChunkAt(container.pos())) return Optional.empty();
                return BlockStructureItemStorage.at(level, container.pos()).map(storage -> storage);
            }
        }
        return Optional.empty();
    }
}
