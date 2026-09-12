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

import java.util.function.Supplier;

/**
 * Normal AE2 Interface output host with a bounded local cache.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2OutputInterfaceBlockEntity extends AE2OutputInterfaceBaseBlockEntity {
    private final AE2OutputResourceStorage<ItemResource> itemStorage;
    private final AE2OutputResourceStorage<FluidResource> fluidStorage;
    final OutputTicker outputTicker;

    AE2OutputInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind) {
        this(pos, state, kind, null, null);
    }

    AE2OutputInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind,
                                  @Nullable Supplier<@Nullable MEStorage> networkSupplier,
                                  @Nullable IActionSource actionSource) {
        super(pos, state, kind);
        Supplier<@Nullable MEStorage> effectiveNetworkSupplier = networkSupplier == null
                ? this::networkStorage : networkSupplier;
        IActionSource effectiveActionSource = actionSource == null
                ? IActionSource.ofMachine(this) : actionSource;
        outputTicker = new OutputTicker();
        mainNode.addService(IGridTickable.class, outputTicker);
        itemStorage = new AE2OutputResourceStorage<>(getStorage(), effectiveNetworkSupplier,
                AE2ItemResourceStorage.adapter(), effectiveActionSource,
                this::onStorageChanged);
        fluidStorage = new AE2OutputResourceStorage<>(getStorage(), effectiveNetworkSupplier,
                AE2FluidResourceStorage.adapter(), effectiveActionSource,
                this::onStorageChanged);
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

            long moved = itemStorage.flushToNetwork(AE2OutputResourceStorage.BOUNDED_FLUSH_OPERATION_LIMIT);
            if (moved < AE2OutputResourceStorage.BOUNDED_FLUSH_OPERATION_LIMIT) {
                moved += fluidStorage.flushToNetwork(
                        AE2OutputResourceStorage.BOUNDED_FLUSH_OPERATION_LIMIT - moved);
            }
            if (getStorage().isEmpty()) return TickRateModulation.SLEEP;
            return moved > 0L ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
        }
    }
}
