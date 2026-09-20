package cn.howxu.mmcr.compat.appliedflux.loaded.storage;

import cn.howxu.mmcr.api.capability.storage.LongValueStorage;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Transactional local cache for AppFlux energy before a network bridge is available.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class FluxEnergyBuffer extends SnapshotJournal<FluxEnergyBuffer.Metadata> {
    public static final long IDLE_SOFT_LIMIT = 10_000L;
    public static final int IDLE_DELAY_TICKS = 200;

    private final LongValueStorage storage;
    private final Runnable onChange;
    private long reserved;
    private long idleExcess;
    private int idleTicks;

    public FluxEnergyBuffer() {
        this(null);
    }

    public FluxEnergyBuffer(Runnable onChange) {
        this.onChange = onChange == null ? () -> {} : onChange;
        storage = new LongValueStorage(Long.MAX_VALUE, Long.MAX_VALUE, this.onChange);
    }

    public LongValueStorage storage() {
        return storage;
    }

    public long amount() {
        return storage.amount();
    }

    public long reserved() {
        return reserved;
    }

    public long idleExcess() {
        return idleExcess;
    }

    public int idleTicks() {
        return idleTicks;
    }

    public boolean isIdleReady() {
        return idleExcess > IDLE_SOFT_LIMIT && idleTicks >= IDLE_DELAY_TICKS;
    }

    public void reserve(long requested, TransactionContext transaction) {
        if (requested <= 0L) throw new IllegalArgumentException("reservation must be positive");
        if (requested > available()) throw new IllegalArgumentException("reservation exceeds local energy");
        updateSnapshots(transaction);
        reserved += requested;
        idleExcess -= Math.min(idleExcess, requested);
        resetIdleTicks();
    }

    public long extract(long requested, TransactionContext transaction) {
        long extracted = storage.extract(requested, transaction);
        if (extracted <= 0L) return 0L;
        updateSnapshots(transaction);
        long reservedExtracted = Math.min(reserved, extracted);
        reserved -= reservedExtracted;
        idleExcess -= Math.min(idleExcess, extracted - reservedExtracted);
        resetIdleTicks();
        return extracted;
    }

    /** Returns only idle, unreserved energy to a network without consuming reservations. */
    public long returnIdle(long requested, TransactionContext transaction) {
        if (requested <= 0L || idleExcess <= 0L) return 0L;
        long returned = storage.extract(Math.min(requested, idleExcess), transaction);
        if (returned <= 0L) return 0L;
        updateSnapshots(transaction);
        idleExcess -= returned;
        resetIdleTicks();
        return returned;
    }

    public long insert(long requested, TransactionContext transaction) {
        long inserted = storage.insert(requested, transaction);
        if (inserted <= 0L) return 0L;
        updateSnapshots(transaction);
        idleExcess = saturatingAdd(idleExcess, inserted);
        resetIdleTicks();
        return inserted;
    }

    public long releaseReservation(long requested, TransactionContext transaction) {
        if (requested <= 0L) return 0L;
        long released = Math.min(requested, reserved);
        if (released <= 0L) return 0L;
        updateSnapshots(transaction);
        reserved -= released;
        idleExcess = saturatingAdd(idleExcess, released);
        resetIdleTicks();
        return released;
    }

    /** Records a server tick while preserving reservations from idle-cache eviction. */
    public void advanceIdle(TransactionContext transaction) {
        if (idleExcess <= IDLE_SOFT_LIMIT || idleTicks >= IDLE_DELAY_TICKS) return;
        updateSnapshots(transaction);
        idleTicks++;
    }

    public void save(ValueOutput output) {
        output.putLong("amount", amount());
        output.putLong("reserved", reserved);
        output.putLong("idle_excess", idleExcess);
        output.putInt("idle_ticks", idleTicks);
    }

    public void load(ValueInput input) {
        storage.setAmount(input.getLongOr("amount", 0L));
        reserved = Math.min(Math.max(0L, input.getLongOr("reserved", 0L)), amount());
        idleExcess = Math.min(Math.max(0L, input.getLongOr("idle_excess", 0L)), available());
        idleTicks = Math.max(0, input.getIntOr("idle_ticks", 0));
    }

    public void setAmount(long amount) {
        storage.setAmount(amount);
        reserved = Math.min(reserved, amount());
        idleExcess = Math.min(idleExcess, available());
        if (idleExcess <= IDLE_SOFT_LIMIT) resetIdleTicks();
    }

    private long available() {
        return amount() - reserved;
    }

    private void resetIdleTicks() {
        idleTicks = 0;
    }

    private static long saturatingAdd(long first, long second) {
        return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
    }

    @Override
    protected Metadata createSnapshot() {
        return new Metadata(reserved, idleExcess, idleTicks);
    }

    @Override
    protected void revertToSnapshot(Metadata snapshot) {
        reserved = snapshot.reserved();
        idleExcess = snapshot.idleExcess();
        idleTicks = snapshot.idleTicks();
    }

    @Override
    protected void onRootCommit(Metadata originalState) {
        if (!originalState.equals(createSnapshot())) onChange.run();
    }

    protected record Metadata(long reserved, long idleExcess, int idleTicks) {
    }
}
