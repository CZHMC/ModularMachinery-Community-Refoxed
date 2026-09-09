package cn.howxu.mmcr.compat.mekanism.loaded;

import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.facet.PersistenceFacet;
import cn.howxu.mmcr.compat.mekanism.MekanismRecipeTypes;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.util.IOType;
import mekanism.api.IContentsListener;
import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.Capabilities;
import mekanism.common.capabilities.heat.BasicHeatCapacitor;
import mekanism.common.capabilities.heat.ITileHeatHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Base block entity for loaded Mekanism heat ports.
 *
 * @author howxu <dev@howxu.cn>
 */
public abstract class HeatPortBlockEntity extends IOPortBlockEntity implements ITileHeatHandler {
    private final BasicHeatCapacitor heatCapacitor;
    private CapabilitySnapshot capabilitySnapshot;

    protected HeatPortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, IOPortKind kind) {
        super(type, pos, state);
        this.heatCapacitor = BasicHeatCapacitor.create(MekanismPortSizes.HEAT_CAPACITY,
                () -> HeatAPI.getAmbientTemp(getLevel(), getBlockPos()), this::markHeatChanged);
    }

    public static BasicHeatCapacitor heatCapacitor(Level level, BlockPos pos, IContentsListener listener) {
        return BasicHeatCapacitor.create(MekanismPortSizes.HEAT_CAPACITY,
                () -> HeatAPI.getAmbientTemp(level, pos), listener);
    }

    public BasicHeatCapacitor heatCapacitor() {
        return heatCapacitor;
    }

    public IHeatHandler heatHandler() {
        return heatCapacitor;
    }

    @Override
    public @Nullable IHeatCapacitor getHeatCapacitor(@Nullable Direction side) {
        if (side == null) return heatCapacitor;
        boolean exposed = kind().definition().bindings().stream()
                .filter(binding -> binding.type().id().equals(MekanismRecipeTypes.HEAT))
                .anyMatch(binding -> isNativeSideExposed(binding, side));
        return exposed ? heatCapacitor : null;
    }

    @Override
    public @Nullable IHeatHandler getAdjacent(Direction side) {
        if (level == null) return null;
        return level.getCapability(Capabilities.HEAT, worldPosition.relative(side), side.getOpposite());
    }

    @Override
    public double getAmbientTemperature(Direction side) {
        return HeatAPI.getAmbientTemp(level, worldPosition.relative(side));
    }

    @Override
    protected void tick() {
        super.tick();
        if (level == null || level.isClientSide()) return;
        try (Transaction transaction = Transaction.openRoot()) {
            simulate(transaction);
            transaction.commit();
        }
    }

    @Override
    public CapabilitySnapshot capabilitySnapshot() {
        if (capabilitySnapshot == null) {
            capabilitySnapshot = new CapabilitySnapshot(kind().definition().bindings().stream()
                    .map(this::createCapability)
                    .toList(), List.of(new HeatPersistenceFacet()));
        }
        return capabilitySnapshot;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private void markHeatChanged() {
        markAutoIOCacheDirty();
        notifyStorageChanged();
        notifyControllerOfInputChange();
    }

    @Override
    public abstract IOType ioType();

    @Override
    public abstract IOPortKind kind();

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        capabilitySnapshot().facets(PersistenceFacet.class)
                .forEach(facet -> facet.save(output.child(facet.stateKey())));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        beginLoadingAdditional();
        try {
            super.loadAdditional(input);
            input.child("heat").ifPresent(child -> capabilitySnapshot().facets(PersistenceFacet.class)
                    .forEach(facet -> facet.load(child)));
        } finally {
            endLoadingAdditional();
        }
    }

    private final class HeatPersistenceFacet implements PersistenceFacet {
        @Override
        public String stateKey() {
            return "heat";
        }

        @Override
        public void save(ValueOutput output) {
            heatCapacitor.serialize(output);
        }

        @Override
        public void load(ValueInput input) {
            heatCapacitor.deserialize(input);
        }
    }
}
