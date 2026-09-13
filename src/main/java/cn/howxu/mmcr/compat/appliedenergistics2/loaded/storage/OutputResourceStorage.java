package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.helpers.externalstorage.GenericStackInv;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.api.capability.status.BuiltinFailureReasons;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.FailureOccurrence;
import cn.howxu.mmcr.api.capability.status.FailurePhase;
import cn.howxu.mmcr.api.capability.status.FailureReason;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2KeyAdapter;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Network-first output view backed by the bounded storage of an AE2 interface.
 *
 * @param <R> resource type exposed by the view
 * @author howxu <dev@howxu.cn>
 */
public final class OutputResourceStorage<R> extends SnapshotJournal<OutputResourceStorage.JournalState>
        implements cn.howxu.mmcr.internal.recipe.OutputResourceStorage<R> {
    /** Fixed upper bound for one output cache flush; intentionally not user-configurable. */
    public static final long BOUNDED_FLUSH_OPERATION_LIMIT = 256L;
    private static final String FAILURE_ID = "ae2_output_interface";

    private final GenericStackInv inventory;
    private final Supplier<@Nullable MEStorage> networkSupplier;
    private final AE2KeyAdapter<R> adapter;
    private final IActionSource actionSource;
    private final Runnable changeCallback;
    private final Map<MEStorage, Map<AEKey, PendingNetwork>> pendingNetwork = new IdentityHashMap<>();
    private boolean localChanged;

    public OutputResourceStorage(GenericStackInv inventory,
                                 Supplier<@Nullable MEStorage> networkSupplier,
                                 AE2KeyAdapter<R> adapter,
                                 IActionSource actionSource,
                                 Runnable changeCallback) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.networkSupplier = Objects.requireNonNull(networkSupplier, "networkSupplier");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.actionSource = Objects.requireNonNull(actionSource, "actionSource");
        this.changeCallback = Objects.requireNonNull(changeCallback, "changeCallback");
    }

    @Override
    public Class<R> resourceType() {
        return adapter.resourceType();
    }

    @Override
    public int size() {
        return inventory.size();
    }

    @Override
    public Object reservationIdentity() {
        return inventory;
    }

    @Override
    @Nullable
    public R resource(int slot) {
        checkSlot(slot);
        AEKey key = inventory.getKey(slot);
        return key == null ? null : adapter.toResource(key).orElse(null);
    }

    @Override
    public long amount(int slot) {
        checkSlot(slot);
        return inventory.getAmount(slot);
    }

    @Override
    public long outputCapacity(R resource) {
        AEKey key = keyOf(resource);
        MEStorage network = networkSupplier.get();
        long capacity = network == null ? 0L : availableNetwork(network, key, Long.MAX_VALUE);
        for (int slot = 0; slot < inventory.size(); slot++) {
            capacity = saturatingAdd(capacity, localAvailable(slot, key));
        }
        return capacity;
    }

    @Override
    public OutputPlan planOutput(R resource, long amount, PlanningReservations reservations, boolean materialize) {
        AEKey key = keyOf(resource);
        if (amount < 0L) throw new IllegalArgumentException("Expected value to be non-negative: " + amount);
        Objects.requireNonNull(reservations, "reservations");
        if (amount == 0L) return new OutputPlan(0L, null);

        MEStorage network = networkSupplier.get();
        long networkAmount = 0L;
        if (network != null) {
            long simulated = availableNetwork(network, key, amount);
            long available = reservations.outputAvailable(network, key, simulated);
            if (available > 0L && reservations.reserveOutput(network, key, available)) {
                networkAmount = Math.min(amount, available);
            }
        }

        long remaining = amount - networkAmount;
        List<LocalPortion> localPortions = new ArrayList<>();
        for (int slot = 0; slot < inventory.size() && remaining > 0L; slot++) {
            long available = localAvailable(slot, key, reservations);
            long portion = Math.min(remaining, available);
            if (portion > 0L && reservations.reserveInsert(this, slot, resource, portion)) {
                localPortions.add(new LocalPortion(slot, portion));
                remaining -= portion;
            }
        }

        long accepted = amount - remaining;
        CapabilityOperation operation = materialize && accepted > 0L
                ? new OutputOperation(resource, key, network, networkAmount, localPortions) : null;
        return new OutputPlan(accepted, operation);
    }

    @Override
    public long capacity(int slot, @Nullable R resource) {
        checkSlot(slot);
        if (resource == null) return inventory.getCapacity(adapter.keyType());
        if (!adapter.resourceType().isInstance(resource)) return 0L;
        AEKey key = adapter.toKey(resource);
        return key == null ? inventory.getCapacity(adapter.keyType()) : inventory.getMaxAmount(key);
    }

    @Override
    public boolean isValid(int slot, R resource) {
        checkSlot(slot);
        AEKey key = keyOf(resource);
        AEKey current = inventory.getKey(slot);
        return inventory.isSupportedType(key.getType()) && (current == null || current.equals(key));
    }

    @Override
    public long insert(int slot, R resource, long amount, TransactionContext transaction) {
        AEKey key = checkedKey(slot, resource, amount);
        if (amount == 0L) return 0L;

        MEStorage network = networkSupplier.get();
        long networkAmount = network == null ? 0L : availableNetwork(network, key, amount);
        if (networkAmount > 0L) stageNetwork(network, key, networkAmount, transaction, slot);

        long localAmount = amount - networkAmount;
        long localInserted = localAmount == 0L ? 0L : insertLocal(slot, key, localAmount, transaction);
        return networkAmount + localInserted;
    }

    @Override
    public long extract(int slot, R resource, long amount, TransactionContext transaction) {
        AEKey key = checkedKey(slot, resource, amount);
        if (amount == 0L || !inventory.canExtract() || !inventory.isSupportedType(key.getType())) return 0L;
        if (!key.equals(inventory.getKey(slot))) return 0L;

        long currentAmount = inventory.getAmount(slot);
        long extracted = Math.min(amount, currentAmount);
        if (extracted == 0L) return 0L;
        updateSnapshots(transaction);
        inventory.updateSnapshots(transaction);
        inventory.setStack(slot, currentAmount == extracted
                ? null : new GenericStack(key, currentAmount - extracted));
        localChanged = true;
        return extracted;
    }

    /**
     * Flushes at most {@code operationLimit} cached units to the current network.
     *
     * @return the amount accepted by the network
     */
    public long flushToNetwork(long operationLimit) {
        if (operationLimit <= 0L || !inventory.canExtract()) return 0L;
        operationLimit = Math.min(operationLimit, BOUNDED_FLUSH_OPERATION_LIMIT);
        MEStorage network = networkSupplier.get();
        if (network == null) return 0L;

        long moved = 0L;
        for (int slot = 0; slot < inventory.size() && moved < operationLimit; slot++) {
            GenericStack stack = inventory.getStack(slot);
            if (stack == null || stack.amount() <= 0L || !adapter.keyType().equals(stack.what().getType())) continue;

            long requested = Math.min(stack.amount(), operationLimit - moved);
            long originalAmount = stack.amount();
            long accepted = 0L;
            try (Transaction transaction = Transaction.openRoot()) {
                long extracted = extractLocal(slot, stack.what(), requested, transaction);
                if (extracted != requested) continue;

                long possible = network.insert(stack.what(), requested, Actionable.SIMULATE, actionSource);
                possible = Math.min(requested, Math.max(0L, possible));
                if (possible == 0L) continue;

                accepted = StorageHelper.poweredInsert(energySource(), network, stack.what(), possible,
                        actionSource);
                accepted = Math.min(possible, Math.max(0L, accepted));
                if (accepted == 0L) continue;

                restoreLocalToAmount(slot, stack.what(), originalAmount - accepted, transaction);
                transaction.commit();
                moved += accepted;
            } catch (RuntimeException exception) {
                if (accepted > 0L) {
                    try {
                        long reverted = network.extract(stack.what(), accepted, Actionable.MODULATE, actionSource);
                        if (reverted != accepted) {
                            throw rollbackFailure(exception);
                        }
                    } catch (RuntimeException rollbackException) {
                        if (rollbackException == exception) throw rollbackException;
                        IllegalStateException failure = rollbackFailure(exception);
                        failure.addSuppressed(rollbackException);
                        throw failure;
                    }
                }
                throw exception;
            }
        }
        return moved;
    }

    private static IllegalStateException rollbackFailure(RuntimeException cause) {
        IllegalStateException failure = new IllegalStateException(
                "Unable to roll back AE2 output network flush");
        failure.addSuppressed(cause);
        return failure;
    }

    private long insertLocal(int slot, AEKey key, long amount, TransactionContext transaction) {
        AEKey current = inventory.getKey(slot);
        if (!inventory.canInsert() || !inventory.isSupportedType(key.getType())
                || current != null && !current.equals(key)) return 0L;
        long currentAmount = inventory.getAmount(slot);
        long available = localAvailable(slot, key);
        long inserted = Math.min(amount, available);
        if (inserted == 0L) return 0L;
        updateSnapshots(transaction);
        inventory.updateSnapshots(transaction);
        inventory.setStack(slot, new GenericStack(key, currentAmount + inserted));
        localChanged = true;
        return inserted;
    }

    private long extractLocal(int slot, AEKey key, long amount, TransactionContext transaction) {
        if (!key.equals(inventory.getKey(slot)) || !inventory.canExtract()) return 0L;
        long currentAmount = inventory.getAmount(slot);
        long extracted = Math.min(amount, currentAmount);
        if (extracted == 0L) return 0L;
        updateSnapshots(transaction);
        inventory.updateSnapshots(transaction);
        inventory.setStack(slot, currentAmount == extracted
                ? null : new GenericStack(key, currentAmount - extracted));
        localChanged = true;
        return extracted;
    }

    private void restoreLocalToAmount(int slot, AEKey key, long amount, TransactionContext transaction) {
        AEKey current = inventory.getKey(slot);
        if (amount < 0L || current != null && !key.equals(current)) {
            throw new IllegalStateException("Unable to restore AE2 output cache");
        }
        if (inventory.getAmount(slot) == amount) return;
        updateSnapshots(transaction);
        inventory.updateSnapshots(transaction);
        inventory.setStack(slot, amount == 0L ? null : new GenericStack(key, amount));
        localChanged = true;
    }

    private long compensateLocally(AEKey key, long amount, Map<Integer, Long> preferredSlots,
                                   long anyFallbackAmount) {
        long inserted = 0L;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Map.Entry<Integer, Long> entry : preferredSlots.entrySet()) {
                if (inserted >= amount) break;
                long requested = Math.min(amount - inserted, entry.getValue());
                inserted += insertCompensation(entry.getKey(), key, requested, transaction);
            }
            if (inserted < amount && anyFallbackAmount > 0L) {
                for (int slot = 0; slot < inventory.size() && inserted < amount; slot++) {
                    inserted += insertCompensation(slot, key, amount - inserted, transaction);
                }
            }
            if (inserted != amount) return 0L;
            transaction.commit();
        }
        return inserted;
    }

    private long localCompensationCapacity(AEKey key, long amount, Map<Integer, Long> preferredSlots,
                                           long anyFallbackAmount) {
        long available = 0L;
        for (Map.Entry<Integer, Long> entry : preferredSlots.entrySet()) {
            if (available >= amount) break;
            long requested = Math.min(amount - available, entry.getValue());
            available = saturatingAdd(available, Math.min(requested, localAvailable(entry.getKey(), key)));
        }
        if (available < amount && anyFallbackAmount > 0L) {
            for (int slot = 0; slot < inventory.size() && available < amount; slot++) {
                available = saturatingAdd(available,
                        Math.min(amount - available, localAvailable(slot, key)));
            }
        }
        return available;
    }

    private long insertCompensation(int slot, AEKey key, long amount, TransactionContext transaction) {
        if (amount <= 0L) return 0L;
        long available = localAvailable(slot, key);
        long inserted = Math.min(amount, available);
        if (inserted == 0L) return 0L;
        long currentAmount = inventory.getAmount(slot);
        inventory.updateSnapshots(transaction);
        inventory.setStack(slot, new GenericStack(key, currentAmount + inserted));
        localChanged = true;
        return inserted;
    }

    private long localAvailable(int slot, AEKey key) {
        checkSlot(slot);
        AEKey current = inventory.getKey(slot);
        if (!inventory.canInsert() || !inventory.isSupportedType(key.getType())
                || current != null && !current.equals(key)) return 0L;
        long currentAmount = inventory.getAmount(slot);
        long capacity = inventory.getMaxAmount(key);
        return capacity <= currentAmount ? 0L : capacity - currentAmount;
    }

    private long localAvailable(int slot, AEKey key, PlanningReservations reservations) {
        if (!inventory.canInsert()) return 0L;
        Object reservedResource = reservations.resource(this, slot);
        if (reservedResource != null) {
            if (!adapter.resourceType().isInstance(reservedResource)
                    || !Objects.equals(adapter.toKey(adapter.resourceType().cast(reservedResource)), key)) {
                return 0L;
            }
        }
        long currentAmount = reservations.amount(this, slot);
        long capacity = capacity(slot, adapter.toResource(key).orElse(null));
        if (currentAmount < 0L || currentAmount > capacity) return 0L;
        return capacity - currentAmount;
    }

    private long availableNetwork(MEStorage network, AEKey key, long amount) {
        long pending = pendingAmount(network, key);
        long request = saturatingAdd(pending, amount);
        long simulated = network.insert(key, request, Actionable.SIMULATE, actionSource);
        if (simulated <= pending) return 0L;
        return Math.min(amount, simulated - pending);
    }

    private void stageNetwork(MEStorage network, AEKey key, long amount, TransactionContext transaction) {
        stageNetwork(network, key, amount, transaction, null);
    }

    private void stageNetwork(MEStorage network, AEKey key, long amount, TransactionContext transaction,
                              @Nullable Integer fallbackSlot) {
        updateSnapshots(transaction);
        Map<AEKey, PendingNetwork> byKey = pendingNetwork.computeIfAbsent(network, ignored -> new HashMap<>());
        PendingNetwork previous = byKey.getOrDefault(key, PendingNetwork.empty());
        Map<Integer, Long> fallbackAmounts = new HashMap<>(previous.fallbackAmounts());
        long anyFallbackAmount = previous.anyFallbackAmount();
        if (fallbackSlot == null) {
            anyFallbackAmount = saturatingAdd(anyFallbackAmount, amount);
        } else {
            fallbackAmounts.merge(fallbackSlot, amount, OutputResourceStorage::saturatingAdd);
        }
        byKey.put(key, new PendingNetwork(saturatingAdd(previous.amount(), amount), fallbackAmounts,
                anyFallbackAmount));
    }

    private long pendingAmount(MEStorage network, AEKey key) {
        return pendingNetwork.getOrDefault(network, Map.of()).getOrDefault(key, PendingNetwork.empty()).amount();
    }

    private IEnergySource energySource() {
        return actionSource.machine()
                .map(IActionHost::getActionableNode)
                .map(IGridNode::getGrid)
                .map(grid -> (IEnergySource) grid.getEnergyService())
                .orElseGet(IEnergySource::empty);
    }

    private AEKey checkedKey(int slot, R resource, long amount) {
        checkSlot(slot);
        if (amount < 0L) throw new IllegalArgumentException("Expected value to be non-negative: " + amount);
        return keyOf(resource);
    }

    private AEKey keyOf(R resource) {
        if (resource == null || !adapter.resourceType().isInstance(resource)) {
            throw new IllegalArgumentException("Expected resource of type " + adapter.resourceType().getName());
        }
        AEKey key = adapter.toKey(resource);
        if (key == null) throw new IllegalArgumentException("Expected resource to be non-empty: " + resource);
        return key;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= inventory.size()) throw new IndexOutOfBoundsException(slot);
    }

    private static long saturatingAdd(long first, long second) {
        return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
    }

    @Override
    protected JournalState createSnapshot() {
        IdentityHashMap<MEStorage, Map<AEKey, PendingNetwork>> pending = new IdentityHashMap<>();
        pendingNetwork.forEach((network, amounts) -> {
            Map<AEKey, PendingNetwork> copied = new HashMap<>();
            amounts.forEach((key, value) -> copied.put(key, value.copy()));
            pending.put(network, copied);
        });
        return new JournalState(pending, localChanged);
    }

    @Override
    protected void revertToSnapshot(JournalState snapshot) {
        pendingNetwork.clear();
        snapshot.pendingNetwork().forEach((network, amounts) -> pendingNetwork.put(network, new HashMap<>(amounts)));
        localChanged = snapshot.localChanged();
    }

    @Override
    protected void onRootCommit(JournalState originalState) {
        boolean changed = false;
        try {
            for (Map.Entry<MEStorage, Map<AEKey, PendingNetwork>> networkEntry : pendingNetwork.entrySet()) {
                Map<AEKey, PendingNetwork> originalAmounts = originalState.pendingNetwork()
                        .getOrDefault(networkEntry.getKey(), Map.of());
                for (Map.Entry<AEKey, PendingNetwork> entry : networkEntry.getValue().entrySet()) {
                    PendingNetwork previous = originalAmounts.getOrDefault(entry.getKey(), PendingNetwork.empty());
                    long amount = entry.getValue().amount() - previous.amount();
                    if (amount > 0L) {
                        Map<Integer, Long> fallbackAmounts = new HashMap<>();
                        entry.getValue().fallbackAmounts().forEach((slot, current) -> {
                            long original = previous.fallbackAmounts().getOrDefault(slot, 0L);
                            long added = current - original;
                            if (added > 0L) fallbackAmounts.put(slot, added);
                        });
                        long anyFallbackAmount = entry.getValue().anyFallbackAmount()
                                - previous.anyFallbackAmount();
                        long localCapacity = localCompensationCapacity(entry.getKey(), amount, fallbackAmounts,
                                Math.max(0L, anyFallbackAmount));
                        long accepted = StorageHelper.poweredInsert(energySource(), networkEntry.getKey(),
                                entry.getKey(), amount, actionSource);
                        accepted = Math.min(amount, Math.max(0L, accepted));
                        long shortfall = amount - accepted;
                        if (shortfall > 0L) {
                            if (localCapacity < shortfall
                                    || compensateLocally(entry.getKey(), shortfall, fallbackAmounts,
                                    Math.max(0L, anyFallbackAmount)) != shortfall) {
                                if (accepted > 0L) {
                                    rollbackNetworkInsert(networkEntry.getKey(), entry.getKey(), accepted);
                                }
                                throw new IllegalStateException("Unable to retain AE2 output network shortfall");
                            }
                        }
                    }
                }
            }
            changed = localChanged;
        } finally {
            pendingNetwork.clear();
            localChanged = false;
        }
        if (changed) changeCallback.run();
    }

    private void rollbackNetworkInsert(MEStorage network, AEKey key, long amount) {
        long reverted = network.extract(key, amount, Actionable.MODULATE, actionSource);
        if (reverted != amount) throw new IllegalStateException("Unable to roll back AE2 output network insert");
    }

    private CapabilityResult failure(FailureReason reason) {
        FailureOccurrence occurrence = FailureOccurrence.at(reason, MMCR.id(FAILURE_ID),
                FailurePhase.CAPABILITY_COMMIT, null, null, Map.of());
        return CapabilityResult.failure(ExecutionStatus.blocked(MMCR.id(FAILURE_ID), MMCR.id(FAILURE_ID), occurrence));
    }

    private record LocalPortion(int slot, long amount) {
    }

    private record PendingNetwork(long amount, Map<Integer, Long> fallbackAmounts, long anyFallbackAmount) {
        private PendingNetwork {
            fallbackAmounts = Map.copyOf(fallbackAmounts);
        }

        private static PendingNetwork empty() {
            return new PendingNetwork(0L, Map.of(), 0L);
        }

        private PendingNetwork copy() {
            return new PendingNetwork(amount, new HashMap<>(fallbackAmounts), anyFallbackAmount);
        }
    }

    record JournalState(IdentityHashMap<MEStorage, Map<AEKey, PendingNetwork>> pendingNetwork,
                        boolean localChanged) {
    }

    private final class OutputOperation implements CapabilityOperation {
        private final AEKey key;
        @Nullable
        private final MEStorage network;
        private final long networkAmount;
        private final List<LocalPortion> localPortions;

        private OutputOperation(R resource, AEKey key, @Nullable MEStorage network,
                                long networkAmount, List<LocalPortion> localPortions) {
            this.key = key;
            this.network = network;
            this.networkAmount = networkAmount;
            this.localPortions = List.copyOf(localPortions);
        }

        @Override
        public CapabilityResult commit(TransactionContext transaction) {
            if (networkAmount > 0L) stageNetwork(network, key, networkAmount, transaction);
            for (LocalPortion portion : localPortions) {
                if (insertLocal(portion.slot(), key, portion.amount(), transaction) != portion.amount()) {
                    return failure(BuiltinFailureReasons.LOCAL_CAPACITY_CHANGED);
                }
            }
            return CapabilityResult.successful();
        }
    }
}
