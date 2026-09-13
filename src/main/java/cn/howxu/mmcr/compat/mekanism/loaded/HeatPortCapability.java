package cn.howxu.mmcr.compat.mekanism.loaded;

import cn.howxu.mmcr.api.capability.CapabilityRequest;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.capability.CapabilityType;
import cn.howxu.mmcr.api.capability.CapabilityView;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.facet.OperationFacet;
import cn.howxu.mmcr.api.capability.facet.PresentationFacet;
import cn.howxu.mmcr.api.capability.facet.SyncFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityRequests;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.presentation.CapabilityDisplay;
import cn.howxu.mmcr.api.capability.status.BuiltinFailureReasons;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.FailureOccurrence;
import cn.howxu.mmcr.api.capability.status.FailurePhase;
import cn.howxu.mmcr.api.capability.status.FailureReason;
import cn.howxu.mmcr.api.compat.mekanism.MekanismFailureReasons;
import cn.howxu.mmcr.compat.mekanism.MekanismRecipeTypes;
import cn.howxu.mmcr.internal.capability.CapabilityFactories;
import cn.howxu.mmcr.util.IOType;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.api.heat.IHeatHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** MMCR capability backed by a loaded Mekanism heat capacitor.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class HeatPortCapability implements LoadedMekanismBridge.HeatPort,
        OperationFacet, PresentationFacet, SyncFacet {
    private static final CapabilityType TYPE = new CapabilityType(MekanismRecipeTypes.HEAT);

    private final IHeatCapacitor heatCapacitor;
    private final IOType ioType;
    private final CapabilityView view;

    public HeatPortCapability(IHeatCapacitor heatCapacitor, IOType ioType) {
        if (heatCapacitor == null) throw new IllegalArgumentException("heatCapacitor must not be null");
        if (ioType == null) throw new IllegalArgumentException("ioType must not be null");
        this.heatCapacitor = heatCapacitor;
        this.ioType = ioType;
        this.view = CapabilityFactories.view(TYPE, directions(),
                Set.of(OperationFacet.class, PresentationFacet.class, SyncFacet.class));
    }

    public HeatPortCapability(HeatPortBlockEntity port) {
        this(port.heatCapacitor(), port.ioType());
    }

    @Override
    public IHeatHandler heatHandler() {
        return heatCapacitor;
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
            return ignored -> failure(BuiltinFailureReasons.UNSUPPORTED_REQUEST);
        }
        return transaction -> {
            double amount = valueRequest.amount();
            if (!valueRequest.insert() && heatCapacitor.getHeat() < amount) {
                double available = Math.max(0D, heatCapacitor.getHeat());
                return failure(MekanismFailureReasons.HEAT_INPUT_MISSING,
                        Map.of("required_heat", Long.toString(valueRequest.amount()),
                                "available_heat", Double.toString(available),
                                "shortfall", Double.toString(Math.max(0D, amount - available))));
            }
            try {
                heatCapacitor.handleHeat(valueRequest.insert() ? amount : -amount, transaction);
            } catch (RuntimeException exception) {
                return failure(MekanismFailureReasons.HEAT_OUTPUT_BLOCKED,
                        Map.of("requested_heat", Long.toString(valueRequest.amount())));
            }
            return CapabilityResult.successful();
        };
    }

    @Override
    public List<CapabilityDisplay> displays(CapabilityView ignored) {
        var unit = MekanismTemperatureDisplay.configuredUnit();
        return List.of(new CapabilityDisplay("heat",
                Double.toString(MekanismTemperatureDisplay.fromKelvin(heatCapacitor.getTemperature(), unit)),
                MekanismTemperatureDisplay.symbol(unit), Optional.empty()));
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

    private CapabilityResult failure(FailureReason reason) {
        return failure(reason, Map.of());
    }

    private CapabilityResult failure(FailureReason reason, Map<String, String> details) {
        FailureOccurrence occurrence = FailureOccurrence.at(reason, type().id(), FailurePhase.CAPABILITY_COMMIT,
                null, null, details);
        return CapabilityResult.failure(ExecutionStatus.blocked(type().id(), type().id(), occurrence));
    }
}
