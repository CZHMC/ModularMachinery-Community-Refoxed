package cn.howxu.mmcr.api.capability;

import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.api.capability.storage.LongValueStorage;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.internal.storage.BulkItemStorage;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies resource reservations use shared storage identities.
 *
 * @author howxu <dev@howxu.cn>
 */
class PlanningReservationsTest {
    @Test
    void defaultStorageUsesItsOwnReservationIdentity() {
        ResourceStorage<?> storage = new BulkItemStorage(64L, () -> {});

        assertThat(storage.reservationIdentity()).isSameAs(storage);
    }

    @Test
    void storagesWithTheSameReservationIdentityShareSlotReservations() {
        Object identity = new Object();
        ResourceStorage<String> first = new AliasedStorage(identity);
        ResourceStorage<String> second = new AliasedStorage(identity);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveInsert(first, 0, "iron", 4L)).isTrue();
        assertThat(reservations.amount(second, 0)).isEqualTo(4L);
        assertThat(reservations.reserveInsert(second, 0, "iron", 7L)).isFalse();
    }

    @Test
    void emptyResourceReadsCopiesAndRejectedWritesDoNotAllocateMaps() {
        ResourceStorage<String> storage = new AliasedStorage(null);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.resource(storage, 0)).isNull();
        assertThat(reservations.amount(storage, 0)).isZero();
        assertThat(reservations.reserveExtract(storage, 0, "iron", 1L)).isFalse();
        assertThat(reservations.reserveInsert(storage, 0, null, 1L)).isFalse();
        assertThat(reservations.reserveInsert(storage, 0, new Object(), 1L)).isFalse();
        assertThat(reservations.reserveInsert(storage, 0, "iron", 0L)).isFalse();
        assertThat(reservations.reserveExtract(storage, 0, "iron", Long.MIN_VALUE)).isFalse();
        assertThat(reservations.reserveInsert(storage, 0, "iron", 11L)).isFalse();
        PlanningReservations copy = reservations.copy();
        assertThat(copy.resource(storage, 0)).isNull();
        assertThat(copy.amount(storage, 0)).isZero();
        assertThat(reservations).extracting("resources", "outputReservations", "values")
                .containsExactly(null, null, null);
        assertThat(copy).extracting("resources", "outputReservations", "values")
                .containsExactly(null, null, null);

        assertThat(copy.reserveInsert(storage, 0, "iron", 4L)).isTrue();
        assertThat(copy.resource(storage, 0)).isEqualTo("iron");
        assertThat(copy.amount(storage, 0)).isEqualTo(4L);
        assertThat(reservations.resource(storage, 0)).isNull();
        assertThat(reservations.amount(storage, 0)).isZero();
    }

    @Test
    void resourceOnlyCopiesKeepSharedIdentitySlotsAndDeeplyIsolateReservations() {
        Object identity = new String("network");
        ResourceStorage<String> first = new AliasedStorage(identity);
        ResourceStorage<String> alias = new AliasedStorage(identity);
        ResourceStorage<String> equalIdentity = new AliasedStorage(new String("network"));
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveInsert(first, 0, "iron", 4L)).isTrue();
        PlanningReservations copy = reservations.copy();
        assertThat(copy.resource(alias, 0)).isEqualTo("iron");
        assertThat(copy.amount(alias, 0)).isEqualTo(4L);
        assertThat(copy.reserveExtract(alias, 0, "iron", 2L)).isTrue();
        assertThat(copy.reserveInsert(alias, 0, "iron", 3L)).isTrue();
        assertThat(copy.reserveInsert(alias, 0, "copper", 1L)).isFalse();
        assertThat(copy.reserveInsert(alias, 1, "copper", 6L)).isTrue();
        assertThat(copy.reserveInsert(equalIdentity, 0, "copper", 1L)).isTrue();
        assertThat(copy.amount(first, 0)).isEqualTo(5L);
        assertThat(copy.amount(first, 1)).isEqualTo(6L);
        assertThat(copy.resource(first, 1)).isEqualTo("copper");
        assertThat(copy.amount(equalIdentity, 0)).isEqualTo(1L);
        assertThat(reservations.amount(alias, 0)).isEqualTo(4L);
        assertThat(reservations.amount(alias, 1)).isZero();
        assertThat(reservations.resource(alias, 1)).isNull();
        assertThat(reservations.amount(equalIdentity, 0)).isZero();
        assertThat(reservations.reserveExtract(first, 0, "iron", 4L)).isTrue();
        assertThat(reservations.amount(alias, 0)).isZero();
        assertThat(copy.amount(alias, 0)).isEqualTo(5L);
        assertThat(first.amount(0)).isZero();
        assertThat(reservations).extracting("outputReservations", "values").containsExactly(null, null);
        assertThat(copy).extracting("outputReservations", "values").containsExactly(null, null);
    }

    @Test
    void fullyPopulatedCopiesIsolateAllReservationKinds() {
        Object identity = new Object();
        ResourceStorage<String> storage = new AliasedStorage(identity, "iron", 6L, 10L);
        LongValueStorage value = new LongValueStorage(10L, 10L, null);
        value.setAmount(6L);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveExtract(storage, 0, "iron", 2L)).isTrue();
        assertThat(reservations.reserveOutput(identity, "iron", 2L)).isTrue();
        assertThat(reservations.reserveValue(value, 2L, false)).isTrue();
        PlanningReservations copy = reservations.copy();
        assertThat(copy.reserveExtract(storage, 0, "iron", 4L)).isTrue();
        assertThat(copy.reserveOutput(identity, "iron", 4L)).isTrue();
        assertThat(copy.reserveValue(value, 4L, false)).isTrue();

        assertThat(copy.amount(storage, 0)).isZero();
        assertThat(copy.outputAvailable(identity, "iron", 10L)).isEqualTo(4L);
        assertThat(copy.valueAvailable(value, false)).isZero();
        assertThat(reservations.resource(storage, 0)).isEqualTo("iron");
        assertThat(reservations.amount(storage, 0)).isEqualTo(4L);
        assertThat(reservations.outputAvailable(identity, "iron", 10L)).isEqualTo(8L);
        assertThat(reservations.valueAvailable(value, false)).isEqualTo(4L);
        assertThat(storage.amount(0)).isEqualTo(6L);
        assertThat(value.amount()).isEqualTo(6L);
    }

    @Test
    void resourceCounterOverflowLeavesSuccessfulReservationsIntact() {
        ResourceStorage<String> empty = new AliasedStorage(new Object(), null, 0L, Long.MAX_VALUE);
        ResourceStorage<String> full = new AliasedStorage(new Object(), "iron", Long.MAX_VALUE, Long.MAX_VALUE);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveInsert(empty, 0, "iron", Long.MAX_VALUE)).isTrue();
        assertThat(reservations.reserveExtract(empty, 0, "iron", Long.MAX_VALUE)).isTrue();
        assertThat(reservations.reserveInsert(empty, 0, "iron", 1L)).isFalse();
        assertThat(reservations.amount(empty, 0)).isZero();
        assertThat(reservations.reserveExtract(full, 0, "iron", Long.MAX_VALUE)).isTrue();
        assertThat(reservations.reserveInsert(full, 0, "iron", Long.MAX_VALUE)).isTrue();
        assertThat(reservations.reserveExtract(full, 0, "iron", 1L)).isFalse();
        assertThat(reservations.amount(full, 0)).isEqualTo(Long.MAX_VALUE);
        PlanningReservations copy = reservations.copy();
        assertThat(copy.amount(empty, 0)).isZero();
        assertThat(copy.amount(full, 0)).isEqualTo(Long.MAX_VALUE);
    }

    private static final class AliasedStorage implements ResourceStorage<String> {
        private final Object identity;
        private final String resource;
        private final long amount;
        private final long capacity;

        private AliasedStorage(Object identity) {
            this(identity, null, 0L, 10L);
        }

        private AliasedStorage(Object identity, String resource, long amount, long capacity) {
            this.identity = identity;
            this.resource = resource;
            this.amount = amount;
            this.capacity = capacity;
        }

        @Override
        public Object reservationIdentity() {
            return identity;
        }

        @Override
        public Class<String> resourceType() {
            return String.class;
        }

        @Override
        public int size() {
            return 2;
        }

        @Override
        public String resource(int slot) {
            return resource;
        }

        @Override
        public long amount(int slot) {
            return amount;
        }

        @Override
        public long capacity(int slot, String resource) {
            return capacity;
        }

        @Override
        public boolean isValid(int slot, String resource) {
            return true;
        }

        @Override
        public long insert(int slot, String resource, long amount, TransactionContext transaction) {
            return 0L;
        }

        @Override
        public long extract(int slot, String resource, long amount, TransactionContext transaction) {
            return 0L;
        }
    }
}
