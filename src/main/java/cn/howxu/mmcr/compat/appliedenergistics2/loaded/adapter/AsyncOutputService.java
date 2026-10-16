package cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.google.common.collect.MapMaker;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Transient accumulator for outputs that bypass any local cache and only
 * contact the AE2 network. The accumulator lives in memory only; it is not
 * serialized and has no persistent fallback.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AsyncOutputService {
    // Weak identity keys: a retired grid must not be retained by the admission ledger.
    private static final Map<MEStorage, Map<AEKey, BigInteger>> NETWORK_RESERVATIONS =
            new MapMaker().weakKeys().makeMap();
    private static final Object RESERVATION_LOCK = new Object();

    private final ConcurrentMap<AEKey, Long> pending = new ConcurrentHashMap<>();
    private final Map<AEKey, Long> reserved = new HashMap<>();
    private @Nullable MEStorage network;
    private @Nullable Map<AEKey, BigInteger> networkReservations;
    private long generation;

    /** Reassigns queued output when the interface changes grids; staged transactions cannot move. */
    public boolean bindNetwork(@Nullable MEStorage network) {
        synchronized (RESERVATION_LOCK) {
            if (this.network == network) return true;
            if (!reserved.isEmpty()) return false;
            if (networkReservations != null) pending.forEach((key, amount) -> adjustReservation(key, -amount));
            this.network = network;
            networkReservations = network == null ? null
                    : NETWORK_RESERVATIONS.computeIfAbsent(network, ignored -> new HashMap<>());
            if (networkReservations != null) pending.forEach(this::adjustReservation);
            return true;
        }
    }

    /** Includes both committed queues and still-transactional admissions from every async port. */
    public long outputCapacity(MEStorage network, AEKey key, IActionSource source) {
        synchronized (RESERVATION_LOCK) {
            if (!bindNetwork(network)) return 0L;
            long capacity = Math.max(0L, network.insert(key, Long.MAX_VALUE, Actionable.SIMULATE, source));
            return BigInteger.valueOf(capacity).subtract(networkReservations.getOrDefault(key, BigInteger.ZERO))
                    .max(BigInteger.ZERO).longValueExact();
        }
    }

    /** Rechecks and claims capacity atomically at the actual transaction commit boundary. */
    public @Nullable Reservation reserve(MEStorage network, AEKey key, long amount, IActionSource source) {
        synchronized (RESERVATION_LOCK) {
            if (outputCapacity(network, key, source) < amount) return null;
            reserved.merge(key, amount, Math::addExact);
            adjustReservation(key, amount);
            return new Reservation(key, amount, generation);
        }
    }

    public void release(Reservation reservation) {
        synchronized (RESERVATION_LOCK) {
            if (reservation.generation != generation) return;
            removeReserved(reservation.key, reservation.amount);
            adjustReservation(reservation.key, -reservation.amount);
        }
    }

    /** Converts a staged admission into deliverable output without claiming its capacity twice. */
    public void submitReserved(Reservation reservation) {
        synchronized (RESERVATION_LOCK) {
            if (reservation.generation != generation) return;
            removeReserved(reservation.key, reservation.amount);
            pending.merge(reservation.key, reservation.amount, Math::addExact);
        }
    }

    /**
     * Adds {@code amount} units of {@code key} to the transient queue.
     *
     * <p>Null keys and non-positive amounts are silently ignored so callers
     * can safely enqueue raw resource data without pre-validating it.</p>
     */
    public void submit(AEKey key, long amount) {
        if (key == null || amount <= 0L) return;
        synchronized (RESERVATION_LOCK) {
            long previous = pending.getOrDefault(key, 0L);
            long next = saturatingAdd(previous, amount);
            pending.put(key, next);
            adjustReservation(key, next - previous);
        }
    }

    /**
     * Attempts to move accumulated units directly into the supplied network,
     * without a powered transfer or interface return inventory. Successful portions are removed
     * from the queue; failed or partially accepted amounts remain.
     *
     * @param storage       the network storage to receive outputs
     * @param source        the action source used for the network insert
     * @param maxOperations the maximum number of distinct keys to attempt this call
     * @return {@code true} if at least one key was accepted by the network
     */
    public synchronized boolean drainTo(MEStorage storage, IActionSource source, int maxOperations) {
        if (storage == null || pending.isEmpty() || maxOperations <= 0) return false;
        if (!bindNetwork(storage)) return false;
        int processed = 0;
        for (Iterator<Map.Entry<AEKey, Long>> iterator = pending.entrySet().iterator();
             iterator.hasNext() && processed < maxOperations; ) {
            Map.Entry<AEKey, Long> entry = iterator.next();
            AEKey key = entry.getKey();
            long amount = entry.getValue();
            if (key == null || amount <= 0L) {
                iterator.remove();
                continue;
            }
            long accepted = storage.insert(key, amount, Actionable.MODULATE, source);
            accepted = Math.clamp(accepted, 0L, amount);
            if (accepted > 0L) {
                synchronized (RESERVATION_LOCK) {
                    long retained = pending.get(key) - accepted;
                    if (retained > 0L) pending.put(key, retained);
                    else pending.remove(key);
                    adjustReservation(key, -accepted);
                }
            }
            processed++;
        }
        return pending.isEmpty();
    }

    /**
     * Drops all queued work. Called from lifecycle hooks such as
     * {@code onChunkUnloaded()} and {@code setRemoved()}.
     */
    public synchronized void clear() {
        synchronized (RESERVATION_LOCK) {
            pending.forEach((key, amount) -> adjustReservation(key, -amount));
            reserved.forEach((key, amount) -> adjustReservation(key, -amount));
            pending.clear();
            reserved.clear();
            generation++;
            network = null;
            networkReservations = null;
        }
    }

    /**
     * @return {@code true} when no units are queued for delivery
     */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    private void removeReserved(AEKey key, long amount) {
        long remaining = reserved.getOrDefault(key, 0L) - amount;
        if (remaining < 0L) throw new IllegalStateException("Async output reservation underflow");
        if (remaining == 0L) reserved.remove(key);
        else reserved.put(key, remaining);
    }

    private void adjustReservation(AEKey key, long delta) {
        if (networkReservations == null) return;
        BigInteger next = networkReservations.getOrDefault(key, BigInteger.ZERO).add(BigInteger.valueOf(delta));
        if (next.signum() < 0) throw new IllegalStateException("Async network reservation underflow");
        if (next.signum() == 0) networkReservations.remove(key);
        else networkReservations.put(key, next);
    }

    /**
     * One transactional admission; lifecycle clearing invalidates outstanding handles.
     *
     * @author howxu <dev@howxu.cn>
     */
    public static final class Reservation {
        private final AEKey key;
        private final long amount;
        private final long generation;

        private Reservation(AEKey key, long amount, long generation) {
            this.key = key;
            this.amount = amount;
            this.generation = generation;
        }
    }

    private static long saturatingAdd(long first, long second) {
        return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
    }
}
