package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2KeyAdapter;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ephemeral transactional MMCR view over the inputs pushed by one AE2 pattern request.
 *
 * @param <R> resource type exposed by the view
 * @author howxu <dev@howxu.cn>
 */
public final class PatternRequestResourceStorage<R> extends SnapshotJournal<PatternRequestResourceStorage.Snapshot>
        implements ResourceStorage<R> {
    private final AE2KeyAdapter<R> adapter;
    private final PatternRequestState state;
    private final List<AEKey> keys;
    private final long[] amounts;
    private boolean active = true;

    public PatternRequestResourceStorage(KeyCounter[] inputHolders, AE2KeyAdapter<R> adapter) {
        this(new PatternRequestState(inputHolders, 1), adapter);
    }

    public PatternRequestResourceStorage(PatternRequestState state, AE2KeyAdapter<R> adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.state = Objects.requireNonNull(state, "state");
        Map<AEKey, Long> totals = aggregate(state.inputHolders());
        List<AEKey> selectedKeys = new ArrayList<>();
        List<Long> selectedAmounts = new ArrayList<>();
        for (Map.Entry<AEKey, Long> entry : totals.entrySet()) {
            if (adapter.keyType().equals(entry.getKey().getType())) {
                selectedKeys.add(entry.getKey());
                selectedAmounts.add(entry.getValue());
            }
        }
        keys = List.copyOf(selectedKeys);
        amounts = new long[selectedAmounts.size()];
        for (int slot = 0; slot < amounts.length; slot++) amounts[slot] = selectedAmounts.get(slot);
    }

    @Override
    public Class<R> resourceType() {
        return adapter.resourceType();
    }

    @Override
    public int size() {
        requireActive();
        return keys.size();
    }

    @Override
    @Nullable
    public R resource(int slot) {
        requireActive();
        return adapter.toResource(keys.get(checkSlot(slot))).orElse(null);
    }

    @Override
    public long amount(int slot) {
        requireActive();
        return amounts[checkSlot(slot)];
    }

    @Override
    public long capacity(int slot, @Nullable R resource) {
        requireActive();
        slot = checkSlot(slot);
        return resource == null || keys.get(slot).equals(keyOf(resource)) ? amounts[slot] : 0L;
    }

    @Override
    public boolean isValid(int slot, R resource) {
        requireActive();
        return keys.get(checkSlot(slot)).equals(keyOf(resource));
    }

    @Override
    public long insert(int slot, R resource, long amount, TransactionContext transaction) {
        checkedKey(slot, resource, amount);
        return 0L;
    }

    @Override
    public long extract(int slot, R resource, long amount, TransactionContext transaction) {
        AEKey key = checkedKey(slot, resource, amount);
        if (amount == 0L || !keys.get(slot).equals(key)) return 0L;
        long extracted = Math.min(amount, amounts[slot]);
        if (extracted == 0L) return 0L;
        updateSnapshots(Objects.requireNonNull(transaction, "transaction"));
        amounts[slot] -= extracted;
        return extracted;
    }

    /** Invalidates this request after its surrounding pattern admission is rejected. */
    public void reject(TransactionContext transaction) {
        requireActive();
        updateSnapshots(Objects.requireNonNull(transaction, "transaction"));
        state.reject(transaction);
        active = false;
    }

    /**
     * Returns every unconsumed request amount to AE2's native return inventory and invalidates this request.
     *
     * @return whether the native return inventory could hold every unconsumed amount exactly
     */
    public boolean accept(PatternReturnResourceStorage<R> returns, TransactionContext transaction) {
        requireActive();
        Objects.requireNonNull(returns, "returns");
        transaction = Objects.requireNonNull(transaction, "transaction");
        if (!adapter.resourceType().equals(returns.resourceType())) {
            reject(transaction);
            return false;
        }

        List<ReturnAllocation<R>> allocations = allocateReturns(returns);
        if (allocations == null) {
            reject(transaction);
            return false;
        }
        for (ReturnAllocation<R> allocation : allocations) {
            long inserted = returns.insert(allocation.slot(), allocation.resource(), allocation.amount(), transaction);
            if (inserted != allocation.amount()) {
                throw new IllegalStateException("Unable to insert exact pattern return amount");
            }
        }
        updateSnapshots(transaction);
        state.accept(keys, transaction);
        active = false;
        return true;
    }

    @Override
    protected Snapshot createSnapshot() {
        return new Snapshot(amounts.clone(), active);
    }

    @Override
    protected void revertToSnapshot(Snapshot snapshot) {
        System.arraycopy(snapshot.amounts(), 0, amounts, 0, amounts.length);
        active = snapshot.active();
    }

    private static Map<AEKey, Long> aggregate(KeyCounter[] inputHolders) {
        if (inputHolders == null) throw new IllegalArgumentException("inputHolders must not be null");
        Map<AEKey, Long> totals = new LinkedHashMap<>();
        for (KeyCounter inputHolder : inputHolders) {
            if (inputHolder == null) throw new IllegalArgumentException("inputHolder must not be null");
            for (var entry : inputHolder) {
                AEKey key = Objects.requireNonNull(entry.getKey(), "pattern input key");
                if (!isSupportedKey(key)) {
                    throw new IllegalArgumentException("Unsupported AE2 pattern input key: " + key);
                }
                long amount = entry.getLongValue();
                if (amount < 0L) throw new IllegalArgumentException("Pattern input amount must be non-negative: " + amount);
                if (amount == 0L) continue;
                try {
                    totals.merge(key, amount, Math::addExact);
                } catch (ArithmeticException exception) {
                    throw new IllegalArgumentException("Pattern input amount exceeds long range", exception);
                }
            }
        }
        return totals;
    }

    private static boolean isSupportedKey(AEKey key) {
        AEKeyType keyType = key.getType();
        return AEKeyType.items().equals(keyType) || AEKeyType.fluids().equals(keyType);
    }

    @Nullable
    private List<ReturnAllocation<R>> allocateReturns(PatternReturnResourceStorage<R> returns) {
        R[] reservedResources = newResourceArray(returns.size());
        long[] reservedAmounts = new long[returns.size()];
        List<ReturnAllocation<R>> allocations = new ArrayList<>();
        for (int requestSlot = 0; requestSlot < keys.size(); requestSlot++) {
            long remaining = amounts[requestSlot];
            if (remaining == 0L) continue;
            R resource = adapter.toResource(keys.get(requestSlot)).orElseThrow(() ->
                    new IllegalStateException("Pattern request key does not match its resource family"));
            long capacity = 0L;
            for (int returnSlot = 0; returnSlot < returns.size() && capacity < remaining; returnSlot++) {
                if (reservedResources[returnSlot] != null && !reservedResources[returnSlot].equals(resource)
                        || !returns.isValid(returnSlot, resource)) continue;
                long current = returns.amount(returnSlot);
                long maximum = returns.capacity(returnSlot, resource);
                long reserved = reservedAmounts[returnSlot];
                if (current < 0L || maximum < current) return null;
                long occupied;
                try {
                    occupied = Math.addExact(current, reserved);
                    capacity = Math.addExact(capacity, Math.max(0L, maximum - occupied));
                } catch (ArithmeticException exception) {
                    return null;
                }
                if (capacity > remaining) capacity = remaining;
                long allocated = Math.min(remaining - allocationAmount(allocations, resource), maximum - occupied);
                if (allocated <= 0L) continue;
                try {
                    reservedAmounts[returnSlot] = Math.addExact(reserved, allocated);
                } catch (ArithmeticException exception) {
                    return null;
                }
                reservedResources[returnSlot] = resource;
                allocations.add(new ReturnAllocation<>(returnSlot, resource, allocated));
            }
            if (capacity < remaining) return null;
        }
        return allocations;
    }

    private long allocationAmount(List<ReturnAllocation<R>> allocations, R resource) {
        long amount = 0L;
        for (ReturnAllocation<R> allocation : allocations) {
            if (!allocation.resource().equals(resource)) continue;
            try {
                amount = Math.addExact(amount, allocation.amount());
            } catch (ArithmeticException exception) {
                throw new IllegalStateException("Pattern return amount exceeds long range", exception);
            }
        }
        return amount;
    }

    @SuppressWarnings("unchecked")
    private R[] newResourceArray(int size) {
        return (R[]) new Object[size];
    }

    private AEKey checkedKey(int slot, R resource, long amount) {
        requireActive();
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

    private int checkSlot(int slot) {
        if (slot < 0 || slot >= keys.size()) throw new IndexOutOfBoundsException(slot);
        return slot;
    }

    private void requireActive() {
        if (!active) throw new IllegalStateException("Pattern request view is no longer active");
        state.requireActive();
    }

    protected record Snapshot(long[] amounts, boolean active) {
    }

    private record ReturnAllocation<R>(int slot, R resource, long amount) {
    }
}
