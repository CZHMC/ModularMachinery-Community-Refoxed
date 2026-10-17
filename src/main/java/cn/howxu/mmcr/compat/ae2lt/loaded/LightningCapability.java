package cn.howxu.mmcr.compat.ae2lt.loaded;

import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.capability.CapabilityRequest;
import cn.howxu.mmcr.api.capability.CapabilityType;
import cn.howxu.mmcr.api.capability.CapabilityView;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.facet.OperationFacet;
import cn.howxu.mmcr.api.capability.facet.ResourceFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityRequests;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.status.BuiltinFailureReasons;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.FailureOccurrence;
import cn.howxu.mmcr.api.capability.status.FailurePhase;
import cn.howxu.mmcr.api.capability.status.FailureReason;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.api.compat.ae2lt.LightningTier;
import cn.howxu.mmcr.compat.ae2lt.LightningRecipeTypes;
import cn.howxu.mmcr.compat.ae2lt.LightningFailureReasons;
import cn.howxu.mmcr.internal.capability.CapabilityFactories;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.util.IOType;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Internal ME-only lightning resource operations; deliberately exposes no adjacent transfer facet.
 * @author howxu <dev@howxu.cn> */
public final class LightningCapability implements MachineCapability, ResourceFacet<LightningTier>, OperationFacet {
    private final IOPortBlockEntity host;
    private final ResourceStorage<LightningTier> storage;
    private final IOType ioType;
    private final CapabilityView view;

    public LightningCapability(ResourceStorage<LightningTier> storage, IOType ioType) {
        this(null, storage, ioType);
    }

    public LightningCapability(IOPortBlockEntity host, ResourceStorage<LightningTier> storage, IOType ioType) {
        this.host = host;
        this.storage = Objects.requireNonNull(storage, "storage");
        this.ioType = Objects.requireNonNull(ioType, "ioType");
        this.view = CapabilityFactories.view(type(), directions(), Set.of(ResourceFacet.class, OperationFacet.class));
    }

    public CapabilityType type() { return new CapabilityType(LightningRecipeTypes.LIGHTNING); }
    public CapabilityDirections directions() { return CapabilityDirections.of(ioType); }
    public CapabilityView view() { return view; }
    public ResourceStorage<LightningTier> storage() { return storage; }
    public Class<LightningTier> resourceType() { return LightningTier.class; }
    public int outputPriority() { return host == null ? 0 : host.kind().outputPriority(); }

    @Override
    public CapabilityOperation prepare(CapabilityRequest request) {
        return CapabilityFactories.operation(this, request);
    }

    @Override
    public CapabilityOperation prepareOperation(CapabilityRequest request) {
        if (!(request instanceof CapabilityRequests.ResourceRequest<?> resources)) {
            return ignored -> failure(BuiltinFailureReasons.UNSUPPORTED_REQUEST);
        }
        return new CapabilityOperation() {
            @Override
            public CapabilityResult commit(TransactionContext transaction) {
                try (Transaction nested = Transaction.open(transaction)) {
                    for (var action : resources.actions()) {
                        if (!(action.resource() instanceof LightningTier tier)) {
                            return failure(BuiltinFailureReasons.WRONG_RESOURCE_TYPE);
                        }
                        long moved = action.insert()
                                ? storage.insert(action.slot(), tier, action.amount(), nested)
                                : storage.extract(action.slot(), tier, action.amount(), nested);
                        if (moved != action.amount()) return failure(action.insert()
                                ? LightningFailureReasons.OUTPUT_BLOCKED : LightningFailureReasons.INPUT_MISSING);
                    }
                    nested.commit();
                }
                return CapabilityResult.successful();
            }

            public CapabilityOperation forParallelism(long parallelism) {
                return parallelism == resources.parallelism() ? this : null;
            }
        };
    }

    private CapabilityResult failure(FailureReason reason) {
        return CapabilityResult.failure(ExecutionStatus.blocked(type().id(), type().id(),
                FailureOccurrence.at(reason, type().id(), FailurePhase.CAPABILITY_COMMIT, null, null, Map.of())));
    }
}
