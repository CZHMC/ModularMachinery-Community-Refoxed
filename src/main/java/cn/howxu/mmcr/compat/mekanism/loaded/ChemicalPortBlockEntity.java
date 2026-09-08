package cn.howxu.mmcr.compat.mekanism.loaded;

import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.facet.PersistenceFacet;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.util.IOType;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.api.chemical.BasicChemicalTank;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.chemical.IChemicalTank;
import mekanism.api.functions.ConstantPredicates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;

import java.util.List;

/** Base block entity for loaded Mekanism chemical ports.
 *
 * @author howxu <dev@howxu.cn>
 */
public abstract class ChemicalPortBlockEntity extends IOPortBlockEntity {
    private final IChemicalTank chemicalTank;
    private CapabilitySnapshot capabilitySnapshot;

    protected ChemicalPortBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                      IOPortKind kind, long capacity, boolean radioactive) {
        super(type, pos, state);
        this.chemicalTank = radioactive
                ? radioactiveChemicalTank(capacity, this::markChemicalChanged)
                : normalChemicalTank(capacity, this::markChemicalChanged);
    }

    public static IChemicalTank normalChemicalTank(long capacity, IContentsListener listener) {
        return BasicChemicalTank.createAllValid(capacity,
                ConstantPredicates.alwaysTrueBi(), ConstantPredicates.alwaysTrueBi(),
                resource -> !resource.value().isRadioactive(), listener);
    }

    public static IChemicalTank radioactiveChemicalTank(long capacity, IContentsListener listener) {
        return BasicChemicalTank.createAllValid(capacity,
                ConstantPredicates.alwaysTrueBi(), ConstantPredicates.alwaysTrueBi(),
                resource -> resource.value().isRadioactive(), listener);
    }

    public IChemicalTank chemicalTank() {
        return chemicalTank;
    }

    public ResourceHandler<ChemicalResource> chemicalHandler(Direction side) {
        return ChemicalPortCapability.resourceHandler(chemicalTank, AutomationType.EXTERNAL, ioType());
    }

    @Override
    public CapabilitySnapshot capabilitySnapshot() {
        if (capabilitySnapshot == null) {
            capabilitySnapshot = new CapabilitySnapshot(kind().definition().bindings().stream()
                    .map(this::createCapability)
                    .toList(), List.of(new ChemicalPersistenceFacet()));
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

    private void markChemicalChanged() {
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
            input.child("chemical").ifPresent(child -> capabilitySnapshot().facets(PersistenceFacet.class)
                    .forEach(facet -> facet.load(child)));
        } finally {
            endLoadingAdditional();
        }
    }

    private final class ChemicalPersistenceFacet implements PersistenceFacet {
        @Override
        public String stateKey() {
            return "chemical";
        }

        @Override
        public void save(ValueOutput output) {
            chemicalTank.serialize(output);
        }

        @Override
        public void load(ValueInput input) {
            chemicalTank.deserialize(input);
        }
    }
}
