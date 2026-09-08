package cn.howxu.mmcr.compat.mekanism.loaded;

import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.facet.PersistenceFacet;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.util.IOType;
import mekanism.api.IContentsListener;
import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.heat.BasicHeatCapacitor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

/** Base block entity for loaded Mekanism heat ports.
 *
 * @author howxu <dev@howxu.cn>
 */
public abstract class HeatPortBlockEntity extends IOPortBlockEntity {
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
