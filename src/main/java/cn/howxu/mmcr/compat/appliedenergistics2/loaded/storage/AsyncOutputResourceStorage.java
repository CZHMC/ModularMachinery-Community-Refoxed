package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.api.capability.status.BuiltinFailureReasons;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.FailureOccurrence;
import cn.howxu.mmcr.api.capability.status.FailurePhase;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2KeyAdapter;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AsyncOutputService;
import cn.howxu.mmcr.internal.recipe.OutputResourceStorage;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Network-only async output view with no persisted local slot storage.
 *
 * <p>All planning routes through {@link OutputResourceStorage#planOutput};
 * ordinary {@code insert} and {@code extract} are zero by design so any
 * stray slot writes are absorbed without affecting the queue.</p>
 *
 * @param <R> resource type exposed by the view
 * @author howxu <dev@howxu.cn>
 */
public final class AsyncOutputResourceStorage<R> extends SnapshotJournal<Integer>
        implements OutputResourceStorage<R> {
    private final Supplier<@Nullable MEStorage> networkSupplier;
    private final AE2KeyAdapter<R> adapter;
    private final AsyncOutputService service;
    private final IActionSource actionSource;
    private final Runnable wakeCallback;
    private final List<AsyncOutputService.Reservation> pending = new ArrayList<>();

    public AsyncOutputResourceStorage(Supplier<@Nullable MEStorage> networkSupplier,
                                      AE2KeyAdapter<R> adapter,
                                      AsyncOutputService service,
                                      IActionSource actionSource,
                                      Runnable wakeCallback) {
        this.networkSupplier = Objects.requireNonNull(networkSupplier, "networkSupplier");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.service = Objects.requireNonNull(service, "service");
        this.actionSource = Objects.requireNonNull(actionSource, "actionSource");
        this.wakeCallback = Objects.requireNonNull(wakeCallback, "wakeCallback");
    }

    @Override
    public Class<R> resourceType() {
        return adapter.resourceType();
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public Object reservationIdentity() {
        return service;
    }

    @Override
    public @Nullable R resource(int slot) {
        return null;
    }

    @Override
    public long amount(int slot) {
        return 0L;
    }

    @Override
    public long capacity(int slot, @Nullable R resource) {
        return 0L;
    }

    @Override
    public boolean isValid(int slot, R resource) {
        return false;
    }

    @Override
    public long insert(int slot, R resource, long amount, TransactionContext transaction) {
        return 0L;
    }

    @Override
    public long extract(int slot, R resource, long amount, TransactionContext transaction) {
        return 0L;
    }

    @Override
    public long outputCapacity(R resource) {
        AEKey key = keyOf(resource);
        MEStorage network = networkSupplier.get();
        if (network == null) {
            service.bindNetwork(null);
            return 0L;
        }
        return service.outputCapacity(network, key, actionSource);
    }

    @Override
    public OutputPlan planOutput(R resource, long amount,
                                 PlanningReservations reservations, boolean materialize) {
        Objects.requireNonNull(reservations, "reservations");
        if (amount < 0L) throw new IllegalArgumentException("Expected value to be non-negative: " + amount);
        if (amount == 0L) return new OutputPlan(0L, null);
        AEKey key;
        try {
            key = keyOf(resource);
        } catch (IllegalArgumentException unsupported) {
            return new OutputPlan(0L, null);
        }

        MEStorage network = networkSupplier.get();
        if (network == null) {
            service.bindNetwork(null);
            return new OutputPlan(0L, null);
        }

        long simulated = service.outputCapacity(network, key, actionSource);
        long available = simulated <= 0L ? 0L : reservations.outputAvailable(network, key, simulated);
        long accepted = Math.clamp(available, 0L, amount);
        if (accepted == 0L) return new OutputPlan(0L, null);
        if (!reservations.reserveOutput(network, key, accepted)) return new OutputPlan(0L, null);

        CapabilityOperation operation = materialize
                ? new AsyncOutputOperation(network, key, accepted) : null;
        return new OutputPlan(accepted, operation);
    }

    @Override
    protected Integer createSnapshot() {
        return pending.size();
    }

    @Override
    protected void revertToSnapshot(Integer snapshot) {
        var reverted = pending.subList(snapshot, pending.size());
        reverted.forEach(service::release);
        reverted.clear();
    }

    @Override
    protected void onRootCommit(Integer originalState) {
        if (pending.isEmpty()) return;
        var committed = List.copyOf(pending);
        pending.clear();
        committed.forEach(service::submitReserved);
        wakeCallback.run();
    }

    private AEKey keyOf(R resource) {
        if (resource == null || !adapter.resourceType().isInstance(resource)) {
            throw new IllegalArgumentException("Expected resource of type " + adapter.resourceType().getName());
        }
        AEKey key = adapter.toKey(resource);
        if (key == null) throw new IllegalArgumentException("Expected resource to be non-empty: " + resource);
        return key;
    }

    private final class AsyncOutputOperation implements CapabilityOperation {
        private final MEStorage plannedNetwork;
        private final AEKey key;
        private final long amount;

        private AsyncOutputOperation(MEStorage plannedNetwork, AEKey key, long amount) {
            this.plannedNetwork = plannedNetwork;
            this.key = key;
            this.amount = amount;
        }

        @Override
        public CapabilityResult commit(TransactionContext transaction) {
            updateSnapshots(transaction);
            var reservation = networkSupplier.get() == plannedNetwork
                    ? service.reserve(plannedNetwork, key, amount, actionSource) : null;
            if (reservation == null) {
                var id = MMCR.id("ae2_async_output");
                return CapabilityResult.failure(ExecutionStatus.blocked(id, id,
                        FailureOccurrence.at(BuiltinFailureReasons.MISSING_OUTPUT, id,
                                FailurePhase.CAPABILITY_COMMIT, null, null, Map.of())));
            }
            pending.add(reservation);
            return CapabilityResult.successful();
        }
    }
}
