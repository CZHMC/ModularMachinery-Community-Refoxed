package cn.howxu.mmcr.compat.mekanism.loaded;

import cn.howxu.mmcr.api.capability.CapabilityRequest;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.capability.CapabilityType;
import cn.howxu.mmcr.api.capability.CapabilityView;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.facet.OperationFacet;
import cn.howxu.mmcr.api.capability.facet.PresentationFacet;
import cn.howxu.mmcr.api.capability.facet.SyncFacet;
import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityRequests;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.presentation.CapabilityDisplay;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.StatusSeverity;
import cn.howxu.mmcr.compat.mekanism.MekanismRecipeTypes;
import cn.howxu.mmcr.internal.capability.CapabilityFactories;
import cn.howxu.mmcr.util.IOType;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IHeatHandler;
import mekanism.api.heat.HeatAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** MMCR capability backed by a loaded Mekanism heat capacitor.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class HeatPortCapability implements LoadedMekanismBridge.HeatPort,
        TransferFacet, OperationFacet, PresentationFacet, SyncFacet {
    private static final CapabilityType TYPE = new CapabilityType(MekanismRecipeTypes.HEAT);

    private final HeatPortBlockEntity port;
    private final IHeatCapacitor heatCapacitor;
    private final IOType ioType;
    private final CapabilityView view;

    public HeatPortCapability(IHeatCapacitor heatCapacitor, IOType ioType) {
        this(null, heatCapacitor, ioType);
    }

    public HeatPortCapability(HeatPortBlockEntity port) {
        this(port, port.heatCapacitor(), port.ioType());
    }

    public HeatPortCapability(@Nullable HeatPortBlockEntity port, IHeatCapacitor heatCapacitor,
                              IOType ioType) {
        if (heatCapacitor == null) throw new IllegalArgumentException("heatCapacitor must not be null");
        if (ioType == null) throw new IllegalArgumentException("ioType must not be null");
        this.port = port;
        this.heatCapacitor = heatCapacitor;
        this.ioType = ioType;
        this.view = CapabilityFactories.view(TYPE, directions(),
                Set.of(TransferFacet.class, OperationFacet.class, PresentationFacet.class, SyncFacet.class));
    }

    @Override
    public IHeatHandler heatHandler() {
        return heatCapacitor;
    }

    @Override
    @Nullable
    public Level level() {
        return port == null ? null : port.getLevel();
    }

    @Override
    public BlockPos position() {
        return port == null ? BlockPos.ZERO : port.getBlockPos();
    }

    @Override
    public long transferLimit() {
        return Math.max(1L, Math.round(heatCapacitor.getHeatCapacity()));
    }

    @Override
    public CapabilityType type() {
        return TYPE;
    }

    @Override
    public CapabilityDirections directions() {
        return CapabilityDirections.of(ioType);
    }

    @Override
    public CapabilityView view() {
        return view;
    }

    @Override
    public CapabilityOperation prepare(CapabilityRequest request) {
        return CapabilityFactories.operation(this, request);
    }

    @Override
    public CapabilityOperation prepareOperation(CapabilityRequest request) {
        if (!(request instanceof CapabilityRequests.ValueRequest valueRequest)) {
            return ignored -> failure("unsupported_request");
        }
        return transaction -> {
            double amount = valueRequest.amount();
            if (!valueRequest.insert() && heatCapacitor.getHeat() < amount) return failure("insufficient_heat");
            heatCapacitor.handleHeat(valueRequest.insert() ? amount : -amount, transaction);
            return CapabilityResult.successful();
        };
    }

    @Override
    public List<CapabilityDisplay> displays(CapabilityView ignored) {
        return List.of(new CapabilityDisplay("heat", Double.toString(heatCapacitor.getTemperature()),
                "K", Optional.empty()));
    }

    @Override
    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeDouble(heatCapacitor.getHeat());
        buffer.writeDouble(heatCapacitor.getHeatCapacity());
    }

    @Override
    public void decode(RegistryFriendlyByteBuf buffer) {
        double heat = buffer.readDouble();
        double capacity = buffer.readDouble();
        if (!Double.isFinite(heat) || heat < 0D || !Double.isFinite(capacity)
                || capacity != heatCapacitor.getHeatCapacity()) {
            throw new IllegalArgumentException("Invalid heat sync state");
        }
        heatCapacitor.setHeat(heat, null);
    }

    static IHeatHandler exposedHeatHandler(IHeatCapacitor heatCapacitor, IOType ioType) {
        return new IHeatHandler() {
            @Override
            public double getTemperature() {
                return heatCapacitor.getTemperature();
            }

            @Override
            public double getInverseConduction() {
                return heatCapacitor.getInverseConduction();
            }

            @Override
            public double getHeatCapacity() {
                return heatCapacitor.getHeatCapacity();
            }

            @Override
            public void handleHeat(double transfer, TransactionContext transaction) {
                if ((ioType == IOType.INPUT && transfer > HeatAPI.EPSILON)
                        || (ioType == IOType.OUTPUT && transfer < -HeatAPI.EPSILON)) {
                    heatCapacitor.handleHeat(transfer, transaction);
                }
            }
        };
    }

    private CapabilityResult failure(String reason) {
        return CapabilityResult.failure(new ExecutionStatus(type().id(), StatusSeverity.BLOCKED,
                type().id(), Map.of("reason", reason)));
    }
}
