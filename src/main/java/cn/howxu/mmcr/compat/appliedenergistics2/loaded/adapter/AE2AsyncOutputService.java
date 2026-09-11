package cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;

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
public final class AE2AsyncOutputService {
    private final ConcurrentMap<AEKey, Long> pending = new ConcurrentHashMap<>();

    /**
     * Adds {@code amount} units of {@code key} to the transient queue.
     *
     * <p>Null keys and non-positive amounts are silently ignored so callers
     * can safely enqueue raw resource data without pre-validating it.</p>
     */
    public void submit(AEKey key, long amount) {
        if (key == null || amount <= 0L) return;
        pending.merge(key, amount, AE2AsyncOutputService::saturatingAdd);
    }

    /**
     * Attempts to move accumulated units into the supplied network using
     * {@link StorageHelper#poweredInsert}. Successful portions are removed
     * from the queue; failed or partially accepted amounts remain.
     *
     * @param storage       the network storage to receive outputs
     * @param source        the action source used for the powered insert
     * @param maxOperations the maximum number of distinct keys to attempt this call
     * @return {@code true} if at least one key was accepted by the network
     */
    public boolean drainTo(MEStorage storage, IActionSource source, int maxOperations) {
        if (storage == null || pending.isEmpty() || maxOperations <= 0) return false;
        IEnergySource energy = energySource(source);
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
            long accepted = StorageHelper.poweredInsert(energy, storage, key, amount, source);
            accepted = Math.min(amount, Math.max(0L, accepted));
            if (accepted > 0L) {
                long remaining = amount - accepted;
                if (remaining <= 0L) {
                    iterator.remove();
                } else {
                    pending.put(key, remaining);
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
    public void clear() {
        pending.clear();
    }

    /**
     * @return {@code true} when no units are queued for delivery
     */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    private static IEnergySource energySource(IActionSource source) {
        return source.machine()
                .map(IActionHost::getActionableNode)
                .map(IGridNode::getGrid)
                .map(grid -> (IEnergySource) grid.getEnergyService())
                .orElseGet(IEnergySource::empty);
    }

    private static long saturatingAdd(long first, long second) {
        return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
    }
}
