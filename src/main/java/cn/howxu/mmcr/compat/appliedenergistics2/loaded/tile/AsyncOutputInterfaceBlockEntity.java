package cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.core.settings.TickRates;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.AsyncOutputResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AsyncOutputService;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.internal.port.IOPortKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Async AE2 interface output host backed by a transient in-memory service.
 *
 * <p>This host deliberately avoids touching the interface logic storage:
 * outputs flow from planning straight into the service accumulator and are
 * drained into the network by a grid ticker. There is no persisted local
 * cache and no offline fallback.</p>
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AsyncOutputInterfaceBlockEntity extends OutputInterfaceBaseBlockEntity {
    private final AsyncOutputService service = new AsyncOutputService();
    private final AsyncOutputResourceStorage<ItemResource> itemStorage;
    private final AsyncOutputResourceStorage<FluidResource> fluidStorage;
    final AsyncOutputTicker asyncTicker;

    public AsyncOutputInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind) {
        this(pos, state, kind, null, null);
    }

    AsyncOutputInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind,
                                    @Nullable Supplier<@Nullable MEStorage> networkSupplier,
                                    @Nullable IActionSource actionSource) {
        super(pos, state, kind);
        Supplier<@Nullable MEStorage> effectiveNetworkSupplier = networkSupplier == null
                ? this::networkStorage : networkSupplier;
        IActionSource effectiveActionSource = actionSource == null
                ? IActionSource.ofMachine(this) : actionSource;
        asyncTicker = new AsyncOutputTicker();
        mainNode.addService(IGridTickable.class, asyncTicker);
        itemStorage = AE2ResourceFamilies.ITEM.asyncOutputView(effectiveNetworkSupplier,
                service, effectiveActionSource, this::wakeAsyncTicker);
        fluidStorage = AE2ResourceFamilies.FLUID.asyncOutputView(effectiveNetworkSupplier,
                service, effectiveActionSource, this::wakeAsyncTicker);
    }

    @Override
    public AsyncOutputResourceStorage<ItemResource> itemStorage() {
        return itemStorage;
    }

    @Override
    public AsyncOutputResourceStorage<FluidResource> fluidStorage() {
        return fluidStorage;
    }

    @Override
    protected void onNetworkChanged() {
        super.onNetworkChanged();
        wakeAsyncTicker();
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        service.clear();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        service.clear();
    }

    private void wakeAsyncTicker() {
        mainNode.ifPresent((grid, node) -> {
            if (service.isEmpty()) {
                grid.getTickManager().sleepDevice(node);
            } else {
                grid.getTickManager().alertDevice(node);
            }
        });
    }

    @Nullable
    private MEStorage networkStorage() {
        IGrid grid = mainNode.getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    final class AsyncOutputTicker implements IGridTickable {
        private static final int DRAIN_BATCH = 8;

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(TickRates.Interface, service.isEmpty());
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!node.isActive()) {
                service.clear();
                return TickRateModulation.SLEEP;
            }

            MEStorage network = networkStorage();
            boolean drained = false;
            if (network != null) {
                IActionSource source = IActionSource.ofMachine(AsyncOutputInterfaceBlockEntity.this);
                drained = service.drainTo(network, source, DRAIN_BATCH);
            }
            if (service.isEmpty()) return TickRateModulation.SLEEP;
            return drained ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
        }
    }
}
