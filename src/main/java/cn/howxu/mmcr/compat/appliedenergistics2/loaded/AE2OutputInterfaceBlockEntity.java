package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.core.settings.TickRates;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2FluidResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ItemResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2OutputResourceStorage;
import cn.howxu.mmcr.internal.port.IOPortKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jetbrains.annotations.Nullable;

/**
 * Normal AE2 Interface output host with a bounded local cache.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2OutputInterfaceBlockEntity extends AE2OutputInterfaceBaseBlockEntity {
    private static final long OPERATION_LIMIT = 256L;

    private final AE2OutputResourceStorage<ItemResource> itemStorage;
    private final AE2OutputResourceStorage<FluidResource> fluidStorage;

    AE2OutputInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind) {
        super(pos, state, kind);
        mainNode.addService(IGridTickable.class, new OutputTicker());
        itemStorage = new AE2OutputResourceStorage<>(getStorage(), this::networkStorage,
                AE2ItemResourceStorage.adapter(), IActionSource.ofMachine(this),
                this::notifyStorageChanged);
        fluidStorage = new AE2OutputResourceStorage<>(getStorage(), this::networkStorage,
                AE2FluidResourceStorage.adapter(), IActionSource.ofMachine(this),
                this::notifyStorageChanged);
    }

    @Override
    public AE2OutputResourceStorage<ItemResource> itemStorage() {
        return itemStorage;
    }

    @Override
    public AE2OutputResourceStorage<FluidResource> fluidStorage() {
        return fluidStorage;
    }

    @Override
    protected void onNetworkChanged() {
        super.onNetworkChanged();
        if (!getStorage().isEmpty()) {
            mainNode.ifPresent((grid, node) -> grid.getTickManager().wakeDevice(node));
        }
    }

    @Nullable
    private MEStorage networkStorage() {
        IGrid grid = mainNode.getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    private final class OutputTicker implements IGridTickable {
        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(TickRates.Interface, getStorage().isEmpty());
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!mainNode.isActive()) return TickRateModulation.SLEEP;

            long moved = itemStorage.flushToNetwork(OPERATION_LIMIT);
            if (moved < OPERATION_LIMIT) {
                moved += fluidStorage.flushToNetwork(OPERATION_LIMIT - moved);
            }
            if (getStorage().isEmpty()) return TickRateModulation.SLEEP;
            return moved > 0L ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
        }
    }
}
