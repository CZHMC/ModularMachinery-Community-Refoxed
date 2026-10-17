package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.network;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2KeyAdapter;
import com.google.common.collect.MapMaker;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * Fixed-slot resource view over the live contents of an AE2 network.
 *
 * @param <R> resource type exposed by the view
 * @author howxu <dev@howxu.cn>
 */
public abstract class NetworkResourceStorage<R> implements ResourceStorage<R> {
    // Both sides must be weak: the journal retains its network while a view/transaction uses it.
    private static final ConcurrentMap<MEStorage, ExtractionJournal> EXTRACTION_JOURNALS =
            new MapMaker().weakKeys().weakValues().makeMap();
    private final MEStorage meStorage;
    private final List<AEKey> keys;
    private final AE2KeyAdapter<R> adapter;
    private final List<R> resources;
    private final long[] amounts;
    private final ExtractionJournal extractionJournal;

    protected NetworkResourceStorage(MEStorage meStorage, List<AEKey> keys, AE2KeyAdapter<R> adapter) {
        this.meStorage = Objects.requireNonNull(meStorage, "meStorage");
        this.extractionJournal = EXTRACTION_JOURNALS.computeIfAbsent(meStorage, ExtractionJournal::new);
        this.keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.resources = this.keys.stream()
                .map(key -> this.adapter.toResource(key).orElse(null))
                .toList();
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
    public final Object reservationSlot(int slot) {
        return keys.get(checkSlot(slot));
    }

    @Override
    public int size() {
        return keys.size();
    }

    @Override
    @Nullable
    public R resource(int slot) {
        checkSlot(slot);
        return resources.get(slot);
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
        return extractionJournal.extract(key, amount, transaction);
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

    /** One server-thread transaction participant for every resource view of a network.
     * @author howxu <dev@howxu.cn> */
    private static final class ExtractionJournal extends SnapshotJournal<Map<AEKey, Long>> {
        private final MEStorage network;
        private final Map<AEKey, Long> pendingExtracts = new LinkedHashMap<>();

        private ExtractionJournal(MEStorage network) {
            this.network = network;
        }

        private long extract(AEKey key, long amount, TransactionContext transaction) {
            long pending = pendingExtracts.getOrDefault(key, 0L);
            long simulationAmount = pending > Long.MAX_VALUE - amount
                    ? Long.MAX_VALUE : pending + amount;
            long available = network.extract(key, simulationAmount, Actionable.SIMULATE, IActionSource.empty());
            long extractable = Math.clamp(available - pending, 0L, amount);
            if (extractable == 0L) return 0L;
            updateSnapshots(Objects.requireNonNull(transaction, "transaction"));
            pendingExtracts.merge(key, extractable, Long::sum);
            return extractable;
        }

        @Override
        protected Map<AEKey, Long> createSnapshot() {
            return new LinkedHashMap<>(pendingExtracts);
        }

        @Override
        protected void revertToSnapshot(Map<AEKey, Long> snapshot) {
            pendingExtracts.clear();
            pendingExtracts.putAll(snapshot);
        }

        @Override
        protected void onRootCommit(Map<AEKey, Long> originalState) {
            Map<AEKey, Long> extractedAmounts = new LinkedHashMap<>();
            try {
                for (var entry : pendingExtracts.entrySet()) {
                    long committed = entry.getValue() - originalState.getOrDefault(entry.getKey(), 0L);
                    if (committed <= 0L) continue;
                    long extracted = network.extract(entry.getKey(), committed, Actionable.MODULATE, IActionSource.empty());
                    if (extracted > 0L) extractedAmounts.put(entry.getKey(), extracted);
                    if (extracted != committed) throw new IllegalStateException("Unable to complete AE2 network extraction");
                }
            } catch (RuntimeException exception) {
                try {
                    for (var entry : extractedAmounts.entrySet()) {
                        long restored = network.insert(entry.getKey(), entry.getValue(), Actionable.MODULATE, IActionSource.empty());
                        if (restored != entry.getValue()) throw new IllegalStateException("Unable to roll back AE2 network extraction");
                    }
                } catch (RuntimeException rollbackException) {
                    IllegalStateException failure = new IllegalStateException("Unable to roll back AE2 network extraction");
                    failure.addSuppressed(exception);
                    failure.addSuppressed(rollbackException);
                    throw failure;
                }
                throw exception;
            } finally {
                pendingExtracts.clear();
            }
        }
    }
}
