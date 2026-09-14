package cn.howxu.mmcr.api.capability.async;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies immutable values used to plan capability work asynchronously.
 *
 * @author howxu <dev@howxu.cn>
 */
class AsyncPlanningFacetTest {
    @Test
    void resource_operation_is_a_value_object_without_a_live_storage_reference() {
        AsyncResourceValue resource = new AsyncResourceValue(MMCR.id("iron_ingot"), "components={}");
        AsyncCapabilityOperation.Resource operation = new AsyncCapabilityOperation.Resource(
                MMCR.id("item"), 2, resource, 4L, false);

        assertThat(operation.capabilityId()).isEqualTo(MMCR.id("item"));
        assertThat(operation.slot()).isEqualTo(2);
        assertThat(operation.resource()).isEqualTo(resource);
        assertThat(operation.amount()).isEqualTo(4L);
        assertThat(operation.insert()).isFalse();
        assertThat(AsyncCapabilityOperation.Resource.class.getRecordComponents()[2].getType())
                .isEqualTo(AsyncResourceValue.class);
    }

    @Test
    void snapshot_slots_are_worker_values_after_capture() {
        var slots = new ArrayList<>(List.of(
                new AsyncCapabilitySnapshot.ResourceSlot(Optional.empty(), 0L, 64L)));
        AsyncCapabilitySnapshot.Resource snapshot = new AsyncCapabilitySnapshot.Resource(
                MMCR.id("item"), slots);
        slots.clear();

        assertThat(snapshot.slots()).hasSize(1);
        assertThat(snapshot.slots().getFirst().resource()).isEmpty();
        assertThat(AsyncCapabilitySnapshot.ResourceSlot.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(Optional.class, long.class, long.class);
    }

    @Test
    void scalar_energy_values_do_not_use_a_resource_identifier() {
        var snapshot = new AsyncCapabilitySnapshot.Scalar(MMCR.id("energy"), 120L, 1_000L);
        var request = new AsyncCapabilityRequest.Scalar(MMCR.id("energy"), 1L, 32L, false);
        var operation = new AsyncCapabilityOperation.Scalar(MMCR.id("energy"), 32L, false);

        assertThat(snapshot.amount()).isEqualTo(120L);
        assertThat(snapshot.capacity()).isEqualTo(1_000L);
        assertThat(request.amount()).isEqualTo(32L);
        assertThat(operation.amount()).isEqualTo(32L);
        assertThat(AsyncCapabilityOperation.Scalar.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(Identifier.class, long.class, boolean.class);
    }

    @Test
    void controlled_entry_rejects_live_facet_access_without_a_server_thread() {
        AtomicBoolean captured = new AtomicBoolean();
        AtomicBoolean committed = new AtomicBoolean();
        AsyncPlanningFacet facet = new AsyncPlanningFacet() {
            @Override
            public AsyncCapabilitySnapshot captureSnapshotOnServerThread() {
                captured.set(true);
                return new AsyncCapabilitySnapshot.Scalar(MMCR.id("energy"), 0L, 1_000L);
            }

            @Override
            public CapabilityResult commitOnServerThread(AsyncCapabilityOperation operation,
                                                          TransactionContext transaction) {
                committed.set(true);
                throw new AssertionError("commit should not run");
            }
        };

        assertThatThrownBy(() -> facet.captureSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("captureSnapshot requires the server thread");
        assertThatThrownBy(() -> facet.commit(
                new AsyncCapabilityOperation.Scalar(MMCR.id("energy"), 32L, false), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("commit requires the server thread");
        assertThat(captured).isFalse();
        assertThat(committed).isFalse();
    }
}
