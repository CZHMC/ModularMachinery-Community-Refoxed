package cn.howxu.mmcr.internal.runtime;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Owns every lane reservation admitted for one external pattern batch.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class PatternStartBatchReservation implements AutoCloseable {
    public enum Status {
        UNAVAILABLE,
        RESERVED,
        /** Every reservation is paid; cancelled lanes need not have activated. */
        COMMITTED
    }

    private final List<PatternStartReservation> reservations;
    private Status status;
    private boolean committing;

    private PatternStartBatchReservation(Status status, List<PatternStartReservation> reservations) {
        this.status = status;
        this.reservations = List.copyOf(reservations);
    }

    public static PatternStartBatchReservation reserved(List<PatternStartReservation> reservations) {
        Objects.requireNonNull(reservations, "reservations");
        if (reservations.isEmpty()) throw new IllegalArgumentException("reservations must not be empty");
        return new PatternStartBatchReservation(Status.RESERVED, reservations);
    }

    public static PatternStartBatchReservation unavailable() {
        return new PatternStartBatchReservation(Status.UNAVAILABLE, List.of());
    }

    public Status status() {
        return status;
    }

    public long parallelism() {
        return reservations.stream().mapToLong(reservation -> reservation.preparedParallelism()).sum();
    }

    public List<PatternStartReservation> reservations() {
        return reservations;
    }

    public boolean commit() {
        return commit(ignored -> { });
    }

    public boolean commit(Consumer<TransactionContext> transactionWrites) {
        Objects.requireNonNull(transactionWrites, "transactionWrites");
        if (committing || status != Status.RESERVED) return false;
        committing = true;
        int acquired = 0;
        try {
            for (PatternStartReservation reservation : reservations) {
                if (!reservation.beginCommitAttempt()) return false;
                acquired++;
            }
            boolean committed = false;
            try (Transaction transaction = Transaction.openRoot()) {
                for (PatternStartReservation reservation : reservations) {
                    if (!reservation.commitPlan(transaction)) return false;
                }
                transactionWrites.accept(transaction);
                transaction.commit();
                committed = true;
            } catch (RuntimeException exception) {
                if (!reservations.stream().allMatch(PatternStartReservation::paid)) return false;
                reservations.forEach(PatternStartReservation::pauseAfterNotificationFailure);
                committed = true;
            } finally {
                if (!committed) rollback();
            }
            status = Status.COMMITTED;
            reservations.forEach(PatternStartReservation::activate);
            return true;
        } finally {
            for (int index = 0; index < acquired; index++) reservations.get(index).endCommitAttempt();
            committing = false;
        }
    }

    public void rollback() {
        if (status != Status.RESERVED) return;
        reservations.forEach(PatternStartReservation::rollback);
        status = Status.UNAVAILABLE;
    }

    @Override
    public void close() {
        rollback();
    }
}
