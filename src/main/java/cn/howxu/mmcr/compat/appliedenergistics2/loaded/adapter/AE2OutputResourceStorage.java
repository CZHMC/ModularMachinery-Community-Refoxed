package cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
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
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.StatusSeverity;
import cn.howxu.mmcr.internal.recipe.OutputResourceStorage;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
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
public final class AE2OutputResourceStorage<R> extends SnapshotJournal<AE2OutputResourceStorage.JournalState>
        implements OutputResourceStorage<R> {
    private static final String FAILURE_ID = "ae2_output_interface";

    private final GenericStackInv inventory;
    private final Supplier<@Nullable MEStorage> networkSupplier;
    private final AE2KeyAdapter<R> adapter;
    private final IActionSource actionSource;
    private final Runnable changeCallback;
    private final Map<MEStorage, Map<AEKey, Long>> pendingNetwork = new IdentityHashMap<>();
    private boolean localChanged;

    public AE2OutputResourceStorage(GenericStackInv inventory,
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
        if (amount == 0L || !inventory.canInsert() || !inventory.isSupportedType(key.getType())) return 0L;

        MEStorage network = networkSupplier.get();
        long networkAmount = network == null ? 0L : availableNetwork(network, key, amount);
        if (networkAmount > 0L) stageNetwork(network, key, networkAmount, transaction);

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
        MEStorage network = networkSupplier.get();
        if (network == null) return 0L;

        long moved = 0L;
        for (int slot = 0; slot < inventory.size() && moved < operationLimit; slot++) {
            GenericStack stack = inventory.getStack(slot);
            if (stack == null || stack.amount() <= 0L || !adapter.keyType().equals(stack.what().getType())) continue;

            long requested = Math.min(stack.amount(), operationLimit - moved);
            long possible = network.insert(stack.what(), requested, Actionable.SIMULATE, actionSource);
            possible = Math.min(requested, Math.max(0L, possible));
            possible = Math.min(possible, localExtractable(slot, stack.what()));
            if (possible == 0L) continue;

            long accepted = StorageHelper.poweredInsert(energySource(), network, stack.what(), possible, actionSource);
            if (accepted == 0L) continue;

            try (var transaction = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                long extracted = extractLocal(slot, stack.what(), accepted, transaction);
                if (extracted != accepted) continue;
                transaction.commit();
            }
            moved += accepted;
        }
        return moved;
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

    private long localExtractable(int slot, AEKey key) {
        return key.equals(inventory.getKey(slot)) ? inventory.getAmount(slot) : 0L;
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
        updateSnapshots(transaction);
        pendingNetwork.computeIfAbsent(network, ignored -> new HashMap<>())
                .merge(key, amount, AE2OutputResourceStorage::saturatingAdd);
    }

    private long pendingAmount(MEStorage network, AEKey key) {
        return pendingNetwork.getOrDefault(network, Map.of()).getOrDefault(key, 0L);
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
        IdentityHashMap<MEStorage, Map<AEKey, Long>> pending = new IdentityHashMap<>();
        pendingNetwork.forEach((network, amounts) -> pending.put(network, new HashMap<>(amounts)));
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
        for (Map.Entry<MEStorage, Map<AEKey, Long>> networkEntry : pendingNetwork.entrySet()) {
            Map<AEKey, Long> originalAmounts = originalState.pendingNetwork()
                    .getOrDefault(networkEntry.getKey(), Map.of());
            for (Map.Entry<AEKey, Long> entry : networkEntry.getValue().entrySet()) {
                long previous = originalAmounts.getOrDefault(entry.getKey(), 0L);
                long amount = entry.getValue() - previous;
                if (amount > 0L) {
                    StorageHelper.poweredInsert(energySource(), networkEntry.getKey(), entry.getKey(), amount,
                            actionSource);
                }
            }
        }
        pendingNetwork.clear();
        boolean changed = localChanged;
        localChanged = false;
        if (changed) changeCallback.run();
    }

    private CapabilityResult failure(String reason) {
        return CapabilityResult.failure(new ExecutionStatus(MMCR.id(FAILURE_ID), StatusSeverity.BLOCKED,
                MMCR.id(FAILURE_ID), Map.of("reason", reason)));
    }

    private record LocalPortion(int slot, long amount) {
    }

    record JournalState(IdentityHashMap<MEStorage, Map<AEKey, Long>> pendingNetwork,
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
                    return failure("local_capacity_changed");
                }
            }
            return CapabilityResult.successful();
        }
    }
}
