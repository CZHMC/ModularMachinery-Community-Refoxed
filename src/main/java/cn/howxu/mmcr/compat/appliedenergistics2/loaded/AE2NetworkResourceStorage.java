package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-slot resource view over the live contents of an AE2 network.
 *
 * @param <R> resource type exposed by the view
 * @author howxu <dev@howxu.cn>
 */
public abstract class AE2NetworkResourceStorage<R> extends SnapshotJournal<Map<AEKey, Long>>
        implements ResourceStorage<R> {
    private final MEStorage meStorage;
    private final List<AEKey> keys;
    private final AE2KeyAdapter<R> adapter;
    private final long[] amounts;
    private final Map<AEKey, Long> pendingExtracts = new HashMap<>();

    protected AE2NetworkResourceStorage(MEStorage meStorage, List<AEKey> keys, AE2KeyAdapter<R> adapter) {
        this.meStorage = Objects.requireNonNull(meStorage, "meStorage");
        this.keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.amounts = new long[this.keys.size()];
    }

    public void onStackChange(AEKey key, long amount) {
        for (int slot = 0; slot < keys.size(); slot++) {
            if (keys.get(slot).equals(key)) amounts[slot] = Math.max(0L, amount);
        }
    }

    @Override
    public Class<R> resourceType() {
        return adapter.resourceType();
    }

    @Override
    public final Object reservationIdentity() {
        return meStorage;
    }

    @Override
    public int size() {
        return keys.size();
    }

    @Override
    @Nullable
    public R resource(int slot) {
        checkSlot(slot);
        return adapter.toResource(keys.get(slot)).orElse(null);
    }

    @Override
    public long amount(int slot) {
        checkSlot(slot);
        return amounts[slot];
    }

    @Override
    public long capacity(int slot, @Nullable R resource) {
        checkSlot(slot);
        return Long.MAX_VALUE;
    }

    @Override
    public boolean isValid(int slot, R resource) {
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

        long pending = pendingExtracts.getOrDefault(key, 0L);
        long simulationAmount = pending > Long.MAX_VALUE - amount
                ? Long.MAX_VALUE : pending + amount;
        long available = meStorage.extract(key, simulationAmount, Actionable.SIMULATE, IActionSource.empty());
        long extractable = Math.min(amount, Math.max(0L, available - pending));
        if (extractable == 0L) return 0L;

        updateSnapshots(transaction);
        pendingExtracts.merge(key, extractable, Long::sum);
        return extractable;
    }

    @Override
    protected Map<AEKey, Long> createSnapshot() {
        return new HashMap<>(pendingExtracts);
    }

    @Override
    protected void revertToSnapshot(Map<AEKey, Long> snapshot) {
        pendingExtracts.clear();
        pendingExtracts.putAll(snapshot);
    }

    @Override
    protected void onRootCommit(Map<AEKey, Long> originalState) {
        for (Map.Entry<AEKey, Long> entry : pendingExtracts.entrySet()) {
            long previous = originalState.getOrDefault(entry.getKey(), 0L);
            long committed = entry.getValue() - previous;
            if (committed > 0L) {
                meStorage.extract(entry.getKey(), committed, Actionable.MODULATE, IActionSource.empty());
            }
        }
        pendingExtracts.clear();
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

    private int checkSlot(int slot) {
        if (slot < 0 || slot >= keys.size()) throw new IndexOutOfBoundsException(slot);
        return slot;
    }
}
