package cn.howxu.mmcr.api.capability.plan;

import cn.howxu.mmcr.api.capability.storage.LongValueStorage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies virtual planning reservations stay separate from committed storage state.
 *
 * @author howxu <dev@howxu.cn>
 */
class PlanningReservationsTest {
    @Test
    void value_reservations_are_virtual_and_copyable() {
        LongValueStorage storage = new LongValueStorage(10L, 10L, null);
        storage.setAmount(5L);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveValue(storage, 3L, false)).isTrue();
        assertThat(storage.amount()).isEqualTo(5L);
        assertThat(reservations.valueAvailable(storage, false)).isEqualTo(2L);

        PlanningReservations copy = reservations.copy();
        assertThat(copy.valueAvailable(storage, false)).isEqualTo(2L);
        assertThat(reservations.reserveValue(storage, 2L, false)).isTrue();
        assertThat(reservations.valueAvailable(storage, false)).isZero();
        assertThat(copy.valueAvailable(storage, false)).isEqualTo(2L);
    }

    @Test
    void failed_reservations_do_not_change_virtual_state() {
        LongValueStorage storage = new LongValueStorage(10L, 5L, null);
        storage.setAmount(5L);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveValue(storage, 6L, false)).isFalse();
        assertThat(reservations.valueAvailable(storage, false)).isEqualTo(5L);
        assertThat(reservations.reserveValue(storage, 6L, true)).isFalse();
        assertThat(reservations.valueAvailable(storage, true)).isEqualTo(5L);
    }

    @Test
    void output_reservations_are_keyed_by_network_and_copyable() {
        Object network = new Object();
        Object key = new Object();
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.outputAvailable(network, key, 5L)).isEqualTo(5L);
        assertThat(reservations.reserveOutput(network, key, 4L)).isTrue();
        assertThat(reservations.outputAvailable(network, key, 5L)).isEqualTo(1L);

        PlanningReservations copy = reservations.copy();
        assertThat(copy.outputAvailable(network, key, 5L)).isEqualTo(1L);
        assertThat(copy.reserveOutput(network, key, 1L)).isTrue();
        assertThat(copy.outputAvailable(network, key, 5L)).isZero();
        assertThat(reservations.outputAvailable(network, key, 5L)).isEqualTo(1L);
    }

    @Test
    void output_reservations_validate_arguments() {
        Object network = new Object();
        Object key = new Object();
        PlanningReservations reservations = new PlanningReservations();

        assertThatThrownBy(() -> reservations.outputAvailable(null, key, 5L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservations.outputAvailable(network, null, 5L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservations.outputAvailable(network, key, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservations.reserveOutput(null, key, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservations.reserveOutput(network, null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(reservations.reserveOutput(network, key, 0L)).isFalse();
        assertThat(reservations.reserveOutput(network, key, -1L)).isFalse();
    }

    @Test
    void output_reservations_aggregate_equal_keys_and_isolate_identities() {
        Object firstNetwork = new Object();
        Object secondNetwork = new Object();
        String firstKey = new String("iron");
        String equalKey = new String("iron");
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveOutput(firstNetwork, firstKey, 4L)).isTrue();
        assertThat(reservations.outputAvailable(firstNetwork, equalKey, 10L)).isEqualTo(6L);
        assertThat(reservations.reserveOutput(firstNetwork, equalKey, 3L)).isTrue();
        assertThat(reservations.outputAvailable(firstNetwork, firstKey, 10L)).isEqualTo(3L);
        assertThat(reservations.outputAvailable(secondNetwork, firstKey, 10L)).isEqualTo(10L);
    }

    @Test
    void output_reservations_reject_reservation_overflow() {
        Object network = new Object();
        Object key = new Object();
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveOutput(network, key, Long.MAX_VALUE)).isTrue();
        assertThat(reservations.reserveOutput(network, key, 1L)).isFalse();
        assertThat(reservations.outputAvailable(network, key, Long.MAX_VALUE)).isZero();
    }
}
