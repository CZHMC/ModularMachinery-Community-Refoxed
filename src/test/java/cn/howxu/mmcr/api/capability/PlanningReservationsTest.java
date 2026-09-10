package cn.howxu.mmcr.api.capability;

import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
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

    private static final class AliasedStorage implements ResourceStorage<String> {
        private final Object identity;

        private AliasedStorage(Object identity) {
            this.identity = identity;
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
            return 1;
        }

        @Override
        public String resource(int slot) {
            return null;
        }

        @Override
        public long amount(int slot) {
            return 0L;
        }

        @Override
        public long capacity(int slot, String resource) {
            return 10L;
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
