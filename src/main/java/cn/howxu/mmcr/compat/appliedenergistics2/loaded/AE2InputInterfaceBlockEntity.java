package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.util.AECableType;
import appeng.core.definitions.AEBlocks;
import appeng.helpers.InterfaceLogic;
import appeng.helpers.InterfaceLogicHost;
import appeng.me.helpers.BlockEntityNodeListener;
import appeng.me.helpers.IGridConnectedBlockEntity;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2FluidResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ItemResourceStorage;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * MMCR host for one AE2 interface logic storage.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2InputInterfaceBlockEntity extends IOPortBlockEntity
        implements InterfaceLogicHost, IGridConnectedBlockEntity {
    private static final IGridNodeListener<AE2InputInterfaceBlockEntity> NODE_LISTENER =
            new BlockEntityNodeListener<>() {
                @Override
                public void onGridChanged(AE2InputInterfaceBlockEntity nodeOwner, IGridNode node) {
                    nodeOwner.logic.gridChanged();
                }
            };

    private final IOPortKind kind;
    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NODE_LISTENER)
            .setInWorldNode(true);
    private final InterfaceLogic logic = new InterfaceLogic(mainNode, this, AEBlocks.INTERFACE.asItem());
    private final AE2ItemResourceStorage itemStorage = new AE2ItemResourceStorage(logic.getStorage());
    private final AE2FluidResourceStorage fluidStorage = new AE2FluidResourceStorage(logic.getStorage());
    private CapabilitySnapshot capabilitySnapshot;

    AE2InputInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind) {
        super(typeForKind(kind), pos, state);
        this.kind = kind;
    }

    @Override
    public IOType ioType() {
        return IOType.INPUT;
    }

    @Override
    public IOPortKind kind() {
        return kind;
    }

    @Override
    public IManagedGridNode getMainNode() {
        return mainNode;
    }

    @Override
    public InterfaceLogic getInterfaceLogic() {
        return logic;
    }

    @Override
    public ItemStack getMainMenuIcon() {
        return AEBlocks.INTERFACE.stack();
    }

    @Override
    public BlockEntity getBlockEntity() {
        return this;
    }

    @Override
    public void saveChanges() {
        if (Transaction.getCurrentOpenedTransaction() != null) return;
        notifyStorageChanged();
        notifyControllerOfInputChange();
    }

    @Override
    public AECableType getCableConnectionType(Direction direction) {
        return logic.getCableConnectionType(direction);
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        if (mainNode.hasGridBooted()) logic.notifyNeighbors();
    }

    @Override
    public ResourceStorage<ItemResource> itemStorage() {
        return itemStorage;
    }

    @Override
    public ResourceStorage<FluidResource> fluidStorage() {
        return fluidStorage;
    }

    @Override
    public CapabilitySnapshot capabilitySnapshot() {
        if (capabilitySnapshot == null) {
            capabilitySnapshot = new CapabilitySnapshot(kind.definition().bindings().stream()
                    .map(this::createCapability)
                    .toList());
        }
        return capabilitySnapshot;
    }

    @Override
    public void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        mainNode.serialize(output);
        logic.writeToNBT(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        beginLoadingAdditional();
        try {
            super.loadAdditional(input);
            mainNode.deserialize(input);
            logic.readFromNBT(input);
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

    @Override
    public void dropContents() {
        if (level == null || level.isClientSide()) return;
        List<ItemStack> drops = new ArrayList<>();
        logic.addDrops(drops);
        for (ItemStack stack : drops) {
            Block.popResource(level, worldPosition, stack);
        }
    }
}
