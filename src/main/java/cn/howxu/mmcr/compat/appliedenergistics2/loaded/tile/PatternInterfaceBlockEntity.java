package cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import cn.howxu.mmcr.MMCR;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import appeng.core.definitions.AEBlocks;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.helpers.BlockEntityNodeListener;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.me.storage.NullInventory;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternInterfaceCraftingMachine;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.OutputResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternReturnResourceStorage;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.runtime.PatternStartReservation;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MMCR IO port hosting AE2's native pattern-provider logic.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class PatternInterfaceBlockEntity extends IOPortBlockEntity
        implements PatternProviderLogicHost, IGridConnectedBlockEntity {
    private static final IGridNodeListener<PatternInterfaceBlockEntity> NODE_LISTENER =
            new BlockEntityNodeListener<>() {
                @Override
                public void onGridChanged(PatternInterfaceBlockEntity host, IGridNode node) {
                    host.onNetworkChanged();
                }
            };

    private final IOPortKind kind;
    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NODE_LISTENER)
            .setInWorldNode(true);
    private final PatternProviderLogic logic = new PatternProviderLogic(mainNode, this);
    private final PatternInterfaceCraftingMachine craftingMachine = new PatternInterfaceCraftingMachine(this);
    private final AtomicInteger nextPatternController = new AtomicInteger();
    private final OutputResourceStorage<ItemResource> itemOutputStorage;
    private final OutputResourceStorage<FluidResource> fluidOutputStorage;

    public PatternInterfaceBlockEntity(BlockPos pos, BlockState state, IOPortKind kind) {
        super(typeForKind(kind), pos, state);
        this.kind = kind;
        // PatternProviderLogic must return completed pattern outputs to the grid itself so AE2 can settle the craft.
        itemOutputStorage = AE2ResourceFamilies.ITEM.patternOutputView(logic.getReturnInv(), () -> null,
                IActionSource.ofMachine(this), this::onNativeReturnInventoryDrained);
        fluidOutputStorage = AE2ResourceFamilies.FLUID.patternOutputView(logic.getReturnInv(), () -> null,
                IActionSource.ofMachine(this), this::onNativeReturnInventoryDrained);
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
    public PatternProviderLogic getLogic() {
        return logic;
    }

    public PatternInterfaceCraftingMachine craftingMachine() {
        return craftingMachine;
    }

    /** Reserves the next eligible linked controller without participating in AE2 provider priority. */
    public PatternStartReservation reservePatternStart(List<MachineOutput> patternOutputs,
                                                       List<MachineCapability> requestCapabilities) {
        if (level == null) return PatternStartReservation.unavailable();
        List<MachineControllerBlockEntity> controllers = linkedControllerPositions().stream()
                .sorted(BlockPos::compareTo)
                .map(level::getBlockEntity)
                .filter(MachineControllerBlockEntity.class::isInstance)
                .map(MachineControllerBlockEntity.class::cast)
                .toList();
        return MachineControllerBlockEntity.reserveNextPatternStart(controllers, nextPatternController,
                getBlockPos(), patternOutputs, requestCapabilities);
    }

    @Override
    public BlockEntity getBlockEntity() {
        return this;
    }

    @Override
    public EnumSet<Direction> getTargets() {
        return EnumSet.allOf(Direction.class);
    }

    @Override
    public AEItemKey getTerminalIcon() {
        return AEItemKey.of(AEBlocks.PATTERN_PROVIDER.stack());
    }

    public ItemStack getMainMenuIcon() {
        return AEBlocks.PATTERN_PROVIDER.stack();
    }

    @Override
    public void saveChanges() {
        if (Transaction.getCurrentOpenedTransaction() != null) return;
        notifyStorageChanged();
    }

    @Override
    public AECableType getCableConnectionType(Direction direction) {
        return AECableType.SMART;
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        onNetworkChanged();
    }

    @Override
    public ResourceStorage<ItemResource> itemStorage() {
        return itemInputStorage();
    }

    @Override
    public ResourceStorage<FluidResource> fluidStorage() {
        return fluidInputStorage();
    }

    public ResourceStorage<ItemResource> itemInputStorage() {
        return AE2ResourceFamilies.ITEM.patternInputView(networkStorage());
    }

    public ResourceStorage<FluidResource> fluidInputStorage() {
        return AE2ResourceFamilies.FLUID.patternInputView(networkStorage());
    }

    public PatternRequestResourceStorage<ItemResource> itemRequestStorage(KeyCounter[] inputHolders) {
        return AE2ResourceFamilies.ITEM.patternRequestView(inputHolders);
    }

    public PatternRequestResourceStorage<FluidResource> fluidRequestStorage(KeyCounter[] inputHolders) {
        return AE2ResourceFamilies.FLUID.patternRequestView(inputHolders);
    }

    public OutputResourceStorage<ItemResource> itemOutputStorage() {
        return itemOutputStorage;
    }

    public OutputResourceStorage<FluidResource> fluidOutputStorage() {
        return fluidOutputStorage;
    }

    public PatternReturnResourceStorage<ItemResource> itemReturnStorage() {
        return AE2ResourceFamilies.ITEM.patternReturnView(logic.getReturnInv());
    }

    public PatternReturnResourceStorage<FluidResource> fluidReturnStorage() {
        return AE2ResourceFamilies.FLUID.patternReturnView(logic.getReturnInv());
    }

    /** Notifies linked controllers after AE2's native return inventory drains. */
    public void onNativeReturnInventoryDrained() {
        MMCR.LOG.info("[ae2-pattern-debug] native return inventory changed at {}", worldPosition);
        notifyStorageChanged();
    }

    @Override
    public CapabilitySnapshot capabilitySnapshot() {
        return new CapabilitySnapshot(kind.definition().bindings().stream()
                .map(this::createCapability)
                .toList());
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
                blockEntity.logic.updatePatterns();
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

    private void onNetworkChanged() {
        if (!logic.getReturnInv().isEmpty() || logic.isBusy()) logic.onMainNodeStateChanged();
    }

    private MEStorage networkStorage() {
        IGrid grid = mainNode.getGrid();
        return grid == null ? NullInventory.of() : grid.getStorageService().getInventory();
    }
}
