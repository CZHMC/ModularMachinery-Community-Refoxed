package cn.howxu.mmcr.publicapi.data;

import cn.howxu.mmcr.internal.api.facade.data.StorageAdapters;
import cn.howxu.mmcr.api.data.view.DataStorage;
import cn.howxu.mmcr.api.data.view.DataRepositoryContext;
import cn.howxu.mmcr.api.data.view.DataValueType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** External implementations must cross the real transaction boundary.
 * @author howxu <dev@howxu.cn>
 */
class RepositoryBridgeTest {
    @Test
    void external_source_journal_and_destination_share_the_borrowed_platform_transaction() {
        var source = new ExternalBalance();
        var nativeStore = new cn.howxu.mmcr.api.data.DataStorage();
        var destination = StorageAdapters.wrap(DataStorage.view(nativeStore));
        var id = Identifier.parse("test:external_transaction");
        Repository repository = new Repository() {
            public Identifier id() { return id; }
            public RepositoryRequest request(RepositoryContext context) {
                return RepositoryRequest.available(id, BlockPos.ZERO, "balance", DataKind.LONG, DataKey.of(12L),
                        new Reservation() {
                            public boolean commit(DataStore.Transaction transaction) {
                                source.take(transaction.context());
                                destination.set("balance", DataKey.of(12L), transaction);
                                return true;
                            }
                            public void cancel() { }
                        });
            }
        };
        var reservation = StorageAdapters.toCore(repository).request(
                new DataRepositoryContext(id, BlockPos.ZERO, "balance", DataValueType.LONG)).reservation().orElseThrow();
        for (boolean commit : List.of(false, true)) {
            try (var transaction = Transaction.openRoot()) {
                var borrowed = DataStorage.Transaction.view(transaction);
                assertSame(transaction, StorageAdapters.wrap(borrowed).context());
                assertTrue(reservation.commit(borrowed));
                assertEquals(0L, source.balance);
                assertTrue(destination.contains("balance"));
                if (commit) transaction.commit();
            }
            assertEquals(commit ? 0L : 12L, source.balance);
            assertEquals(commit, destination.contains("balance"));
        }
    }

    /** External resource using NeoForge's journal rather than MMCR storage.
     * @author howxu <dev@howxu.cn>
     */
    private static final class ExternalBalance extends SnapshotJournal<Long> {
        private long balance = 12L;
        void take(TransactionContext context) { updateSnapshots(context); balance = 0L; }
        @Override protected Long createSnapshot() { return balance; }
        @Override protected void revertToSnapshot(Long snapshot) { balance = snapshot; }
    }

    @Test
    void user_reservation_writes_share_rollback_and_root_commit_notification() {
        AtomicInteger notifications = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        var nativeStore = new cn.howxu.mmcr.api.data.DataStorage(ignored -> notifications.incrementAndGet());
        DataStore destination = StorageAdapters.wrap(DataStorage.view(nativeStore));
        Identifier id = Identifier.parse("test:repository");
        Repository repository = new Repository() {
            public Identifier id() { return id; }
            public RepositoryRequest request(RepositoryContext context) {
                return RepositoryRequest.available(id(), context.controllerPos(), context.key(), context.requestedType(),
                        DataKey.of(12L), new Reservation() {
                            public boolean commit(DataStore.Transaction transaction) {
                                return destination.set(context.key(), DataKey.of(12L), transaction);
                            }
                            public void cancel() { cancellations.incrementAndGet(); }
                        });
            }
        };
        var request = StorageAdapters.toCore(repository).request(
                new DataRepositoryContext(id, BlockPos.ZERO, "balance", DataValueType.LONG));
        var reservation = request.reservation().orElseThrow();
        try (var transaction = Transaction.openRoot()) {
            assertTrue(reservation.commit(DataStorage.Transaction.view(transaction)));
            assertEquals(12L, destination.get("balance").orElseThrow().longValue());
            assertEquals(0, notifications.get());
        }
        assertFalse(destination.contains("balance"));
        try (var transaction = Transaction.openRoot()) {
            assertTrue(reservation.commit(DataStorage.Transaction.view(transaction)));
            assertFalse(reservation.commit(DataStorage.Transaction.view(transaction)));
            transaction.commit();
        }
        assertEquals(1, notifications.get());
        assertEquals(12L, destination.get("balance").orElseThrow().longValue());
        reservation.cancel();
        assertEquals(1, cancellations.get());
    }

    @Test
    void nested_values_are_typed_immutable_and_round_trip_without_object_handles() {
        DataKey value = DataKey.map(Map.of("list", DataKey.list(List.of(DataKey.of(7L), DataKey.of("value")))));
        DataKey roundTrip = StorageAdapters.wrap(StorageAdapters.unwrap(value));
        assertEquals(value, roundTrip);
        var list = roundTrip.asMap().orElseThrow().get("list").asList().orElseThrow();
        assertEquals("value", list.get(1).stringValue());
        assertThrows(UnsupportedOperationException.class, () -> list.add(DataKey.of(false)));
        assertThrows(IllegalStateException.class, list.getFirst()::stringValue);
        assertThrows(IllegalArgumentException.class, () -> DataKey.of(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> RepositoryRequest.unavailable(
                Identifier.parse("test:repository"), BlockPos.ZERO, "value", DataKind.INT, DataKey.of(1L)));
    }
}
