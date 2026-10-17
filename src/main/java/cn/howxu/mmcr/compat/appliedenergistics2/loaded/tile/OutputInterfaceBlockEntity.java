package cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.MEStorage;
import appeng.core.settings.TickRates;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamily;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.OutputResourceStorage;
import cn.howxu.mmcr.internal.port.IOPortKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normal AE2 Interface output host with a bounded local cache.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class OutputInterfaceBlockEntity extends OutputInterfaceBaseBlockEntity {
    private final Map<AE2ResourceFamily<?>, OutputResourceStorage<?>> resourceStorages = new LinkedHashMap<>();
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
        for (var family : AE2ResourceFamilies.all()) {
            resourceStorages.put(family, family.outputView(getStorage(), effectiveNetworkSupplier,
                    effectiveActionSource, this::onStorageChanged));
        }
    }

    @Override
    public OutputResourceStorage<ItemResource> itemStorage() {
        return resourceStorage(AE2ResourceFamilies.ITEM);
    }

    @Override
    public OutputResourceStorage<FluidResource> fluidStorage() {
        return resourceStorage(AE2ResourceFamilies.FLUID);
    }

    @SuppressWarnings("unchecked")
    public <R> OutputResourceStorage<R> resourceStorage(AE2ResourceFamily<R> family) {
        return (OutputResourceStorage<R>) resourceStorages.get(family);
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

            long moved = 0L;
            for (var storage : resourceStorages.values()) {
                long remaining = OutputResourceStorage.BOUNDED_FLUSH_OPERATION_LIMIT - moved;
                if (remaining <= 0L) break;
                moved += storage.flushToNetwork(remaining);
            }
            if (getStorage().isEmpty()) return TickRateModulation.SLEEP;
            return moved > 0L ? TickRateModulation.FASTER : TickRateModulation.SLOWER;
        }
    }
}
