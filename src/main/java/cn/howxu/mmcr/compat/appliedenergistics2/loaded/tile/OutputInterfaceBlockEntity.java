package cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.core.settings.TickRates;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.FluidResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.ItemResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.OutputResourceStorage;
import cn.howxu.mmcr.internal.port.IOPortKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Normal AE2 Interface output host with a bounded local cache.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class OutputInterfaceBlockEntity extends OutputInterfaceBaseBlockEntity {
    private final OutputResourceStorage<ItemResource> itemStorage;
    private final OutputResourceStorage<FluidResource> fluidStorage;
    public final OutputTicker outputTicker;

    public OutputInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind) {
        this(pos, state, kind, null, null);
    }

    public OutputInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind,
                                      @Nullable Supplier<@Nullable MEStorage> networkSupplier,
                                      @Nullable IActionSource actionSource) {
        super(pos, state, kind);
        Supplier<@Nullable MEStorage> effectiveNetworkSupplier = networkSupplier == null
                ? this::networkStorage : networkSupplier;
        IActionSource effectiveActionSource = actionSource == null
                ? IActionSource.ofMachine(this) : actionSource;
        outputTicker = new OutputTicker();
        mainNode.addService(IGridTickable.class, outputTicker);
        itemStorage = new OutputResourceStorage<>(getStorage(), effectiveNetworkSupplier,
                ItemResourceStorage.adapter(), effectiveActionSource,
                this::onStorageChanged);
        fluidStorage = new OutputResourceStorage<>(getStorage(), effectiveNetworkSupplier,
                FluidResourceStorage.adapter(), effectiveActionSource,
                this::onStorageChanged);
    }

    @Override
    public OutputResourceStorage<ItemResource> itemStorage() {
        return itemStorage;
    }

    @Override
    public OutputResourceStorage<FluidResource> fluidStorage() {
        return fluidStorage;
    }

    @Override
    protected void onNetworkChanged() {
        super.onNetworkChanged();
        wakeOutputTicker();
    }

    private void onStorageChanged() {
        notifyStorageChanged();
        wakeOutputTicker();
    }

    private void wakeOutputTicker() {
        mainNode.ifPresent((grid, node) -> {
            if (getStorage().isEmpty()) {
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

    final class OutputTicker implements IGridTickable {
        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(TickRates.Interface, getStorage().isEmpty());
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
            if (!node.isActive()) return TickRateModulation.SLEEP;

            long moved = itemStorage.flushToNetwork(OutputResourceStorage.BOUNDED_FLUSH_OPERATION_LIMIT);
            if (moved < OutputResourceStorage.BOUNDED_FLUSH_OPERATION_LIMIT) {
                moved += fluidStorage.flushToNetwork(
                        OutputResourceStorage.BOUNDED_FLUSH_OPERATION_LIMIT - moved);
            }
            if (getStorage().isEmpty()) return TickRateModulation.SLEEP;
            return moved > 0L ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
        }
    }
}
