package cn.howxu.mmcr.compat.appliedflux.loaded.tile;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.util.AECableType;
import appeng.me.helpers.BlockEntityNodeListener;
import appeng.me.helpers.IGridConnectedBlockEntity;
import cn.howxu.mmcr.compat.appliedflux.loaded.FluxEnergyNetwork;
import cn.howxu.mmcr.compat.appliedflux.loaded.storage.FluxEnergyBuffer;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Common no-menu AE2 grid-node lifecycle for AppFlux energy ports.
 *
 * @author howxu <dev@howxu.cn>
 */
public abstract class FluxInterfaceBlockEntity extends IOPortBlockEntity implements IGridConnectedBlockEntity {
    private static final String ENERGY_CACHE_KEY = "energy_cache";
    private static final IGridNodeListener<FluxInterfaceBlockEntity> NODE_LISTENER =
            new BlockEntityNodeListener<>() {
                @Override
                public void onGridChanged(FluxInterfaceBlockEntity nodeOwner, IGridNode node) {
                    nodeOwner.onNetworkChanged();
                }
            };

    protected final IOPortKind kind;
    protected final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NODE_LISTENER)
            .setInWorldNode(true)
            .setFlags(GridFlags.REQUIRE_CHANNEL);

    protected FluxInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind) {
        super(typeForKind(kind), pos, state);
        this.kind = kind;
    }

    @Override
    public final IOPortKind kind() {
        return kind;
    }

    @Override
    public final IManagedGridNode getMainNode() {
        return mainNode;
    }

    public final BlockEntity getBlockEntity() {
        return this;
    }

    @Override
    public AECableType getCableConnectionType(Direction direction) {
        return AECableType.SMART;
    }

    @Override
    public void saveChanges() {
        if (Transaction.getCurrentOpenedTransaction() == null) notifyStorageChanged();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        wakeNode();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        mainNode.serialize(output);
        energyBuffer().save(output.child(ENERGY_CACHE_KEY));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        beginLoadingAdditional();
        try {
            super.loadAdditional(input);
            mainNode.deserialize(input);
            input.child(ENERGY_CACHE_KEY).ifPresent(energyBuffer()::load);
        } finally {
            endLoadingAdditional();
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        GridHelper.onFirstTick(this, blockEntity -> {
            if (blockEntity.getLevel() != null) {
                blockEntity.mainNode.create(blockEntity.getLevel(), blockEntity.getBlockPos());
            }
        });
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        mainNode.destroy();
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        mainNode.destroy();
    }

    protected final FluxEnergyNetwork network() {
        IGrid grid = mainNode.getGrid();
        return grid == null ? null : new FluxEnergyNetwork(grid.getStorageService(), IActionSource.ofMachine(this));
    }

    protected final void wakeNode() {
        mainNode.ifPresent((grid, node) -> grid.getTickManager().alertDevice(node));
    }

    protected abstract FluxEnergyBuffer energyBuffer();

    protected abstract void onNetworkChanged();
}
