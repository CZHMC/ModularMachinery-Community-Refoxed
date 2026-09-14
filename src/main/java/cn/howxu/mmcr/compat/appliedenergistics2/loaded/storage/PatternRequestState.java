package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import java.util.List;

/**
 * Shared transactional state for every typed view of one AE2 pattern request.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class PatternRequestState extends SnapshotJournal<PatternRequestState.Snapshot> {
    private final KeyCounter[] inputHolders;
    private int activeViews;
    private boolean active = true;

    public PatternRequestState(KeyCounter[] inputHolders, int viewCount) {
        if (inputHolders == null) throw new IllegalArgumentException("inputHolders must not be null");
        if (viewCount < 1) throw new IllegalArgumentException("viewCount must be positive");
        this.inputHolders = inputHolders.clone();
        for (KeyCounter inputHolder : this.inputHolders) {
            if (inputHolder == null) throw new IllegalArgumentException("inputHolder must not be null");
        }
        activeViews = viewCount;
    }

    void requireActive() {
        if (!active) throw new IllegalStateException("Pattern request view is no longer active");
    }

    void accept(List<AEKey> keys, TransactionContext transaction) {
        updateSnapshots(transaction);
        for (AEKey key : keys) {
            for (KeyCounter inputHolder : inputHolders) {
                long amount = inputHolder.get(key);
                if (amount > 0L) inputHolder.remove(key, amount);
            }
        }
        if (--activeViews == 0) active = false;
    }

    void reject(TransactionContext transaction) {
        updateSnapshots(transaction);
        active = false;
    }

    KeyCounter[] inputHolders() {
        return inputHolders;
    }

    @Override
    protected Snapshot createSnapshot() {
        KeyCounter[] counters = new KeyCounter[inputHolders.length];
        for (int index = 0; index < inputHolders.length; index++) {
            counters[index] = new KeyCounter();
            counters[index].addAll(inputHolders[index]);
        }
        return new Snapshot(active, activeViews, counters);
    }

    @Override
    protected void revertToSnapshot(Snapshot snapshot) {
        active = snapshot.active();
        activeViews = snapshot.activeViews();
        for (int index = 0; index < inputHolders.length; index++) {
            inputHolders[index].clear();
            inputHolders[index].addAll(snapshot.counters()[index]);
        }
    }

    protected record Snapshot(boolean active, int activeViews, KeyCounter[] counters) {
    }
}
