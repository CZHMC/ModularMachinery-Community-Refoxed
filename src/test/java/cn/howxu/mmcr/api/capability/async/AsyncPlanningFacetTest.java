package cn.howxu.mmcr.api.capability.async;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies immutable values used to plan capability work asynchronously.
 *
 * @author howxu <dev@howxu.cn>
 */
class AsyncPlanningFacetTest {
    @Test
    void resource_operation_is_a_value_object_without_a_live_storage_reference() {
        AsyncResourceValue resource = new AsyncResourceValue(MMCR.id("iron_ingot"), "components={}");
        AsyncCapabilityOperation operation = new AsyncCapabilityOperation.Resource(
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
                new AsyncCapabilitySnapshot.ResourceSlot(new AsyncResourceValue(MMCR.id("empty"), ""), 0L, 64L)));
        AsyncCapabilitySnapshot.Resource snapshot = new AsyncCapabilitySnapshot.Resource(
                MMCR.id("item"), slots);
        slots.clear();

        assertThat(snapshot.slots()).hasSize(1);
        assertThat(snapshot.slots().getFirst().resource()).isInstanceOf(AsyncResourceValue.class);
        assertThat(AsyncCapabilitySnapshot.ResourceSlot.class.getRecordComponents())
                .extracting(RecordComponent::getType)
                .containsExactly(AsyncResourceValue.class, long.class, long.class);
    }

    @Test
    void planning_accepts_only_the_worker_safe_request_contract() throws NoSuchMethodException {
        var request = new AsyncCapabilityRequest.Resource(MMCR.id("item"), 1L, List.of(
                new AsyncResourceAction(new AsyncResourceValue(MMCR.id("iron_ingot"), "components={}"), 4L, false)));

        assertThat(request.actions()).singleElement().satisfies(action ->
                assertThat(action.resource()).isInstanceOf(AsyncResourceValue.class));
        assertThat(AsyncPlanningFacet.class
                .getMethod("plan", AsyncCapabilitySnapshot.class, AsyncCapabilityRequest.class)
                .getParameterTypes()[1]).isEqualTo(AsyncCapabilityRequest.class);
    }
}
