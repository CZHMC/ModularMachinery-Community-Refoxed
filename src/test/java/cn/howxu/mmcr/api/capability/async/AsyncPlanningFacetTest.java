package cn.howxu.mmcr.api.capability.async;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
    void worker_planner_accepts_and_returns_only_async_value_objects() throws Exception {
        AsyncCapabilityPlanner planner = new AsyncCapabilityPlanner.Scalar(MMCR.id("energy"));

        var snapshot = new AsyncCapabilitySnapshot.Scalar(MMCR.id("energy"), 120L, 1_000L);
        var request = new AsyncCapabilityRequest.Scalar(MMCR.id("energy"), 1L, 32L, false);
        Optional<AsyncCapabilityOperation> planned = CompletableFuture.supplyAsync(() -> planner.plan(snapshot, request)).get();

        assertThat(planned).contains(new AsyncCapabilityOperation.Scalar(MMCR.id("energy"), 32L, false));
    }

    @Test
    void worker_planner_is_a_sealed_value_descriptor() {
        assertThat(AsyncCapabilityPlanner.class.isSealed()).isTrue();
        assertThat(Arrays.stream(AsyncCapabilityPlanner.class.getPermittedSubclasses()))
                .containsExactlyInAnyOrder(AsyncCapabilityPlanner.Resource.class, AsyncCapabilityPlanner.Scalar.class);
        assertThat(AsyncCapabilityPlanner.Resource.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(Identifier.class);
        assertThat(AsyncCapabilityPlanner.Scalar.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(Identifier.class);
    }

    @Test
    void public_facet_entries_are_final_and_live_hooks_cannot_be_called_directly() throws Exception {
        assertThat(AsyncPlanningFacet.class.isInterface()).isFalse();
        assertThat(Modifier.isFinal(AsyncPlanningFacet.class.getMethod("captureSnapshot").getModifiers())).isTrue();
        assertThat(Modifier.isFinal(AsyncPlanningFacet.class.getMethod("workerPlanner").getModifiers())).isTrue();
        assertThat(Modifier.isFinal(AsyncPlanningFacet.class.getMethod("commit", AsyncCapabilityOperation.class,
                TransactionContext.class).getModifiers())).isTrue();

        assertThatThrownBy(() -> AsyncPlanningFacet.class.getMethod("captureSnapshotOnServerThread"))
                .isInstanceOf(NoSuchMethodException.class);
        assertThatThrownBy(() -> AsyncPlanningFacet.class.getMethod("commitOnServerThread",
                AsyncCapabilityOperation.class, TransactionContext.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    void public_entries_reject_worker_thread_access_before_live_hooks_run() {
        TestFacet facet = new TestFacet();

        assertThatThrownBy(() -> facet.captureSnapshot())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("captureSnapshot requires the server thread");
        assertThatThrownBy(() -> facet.workerPlanner())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("workerPlanner requires the server thread");
        assertThatThrownBy(() -> facet.commit(
                new AsyncCapabilityOperation.Scalar(MMCR.id("energy"), 32L, false), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("commit requires the server thread");
        assertThat(facet.captured).isFalse();
        assertThat(facet.exported).isFalse();
        assertThat(facet.committed).isFalse();
    }

    private static final class TestFacet extends AsyncPlanningFacet {
        private final AtomicBoolean captured = new AtomicBoolean();
        private final AtomicBoolean exported = new AtomicBoolean();
        private final AtomicBoolean committed = new AtomicBoolean();

        @Override
        protected AsyncCapabilitySnapshot captureSnapshotOnServerThread() {
            captured.set(true);
            return new AsyncCapabilitySnapshot.Scalar(MMCR.id("energy"), 0L, 1_000L);
        }

        @Override
        protected AsyncCapabilityPlanner workerPlannerOnServerThread() {
            exported.set(true);
            return new AsyncCapabilityPlanner.Scalar(MMCR.id("energy"));
        }

        @Override
        protected CapabilityResult commitOnServerThread(AsyncCapabilityOperation operation,
                                                         TransactionContext transaction) {
            committed.set(true);
            throw new AssertionError("commit should not run");
        }
    }
}
