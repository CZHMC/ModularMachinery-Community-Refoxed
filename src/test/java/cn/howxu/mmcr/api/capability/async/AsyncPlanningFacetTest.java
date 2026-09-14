package cn.howxu.mmcr.api.capability.async;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.test.TestBootstrap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies immutable values used to plan capability work asynchronously.
 *
 * @author howxu <dev@howxu.cn>
 */
class AsyncPlanningFacetTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void resource_operation_is_a_value_object_without_a_live_storage_reference() {
        AsyncCapabilityOperation operation = new AsyncCapabilityOperation.Resource(
                MMCR.id("item"), 2, ItemResource.of(Items.IRON_INGOT), 4L, false);

        assertThat(operation.capabilityId()).isEqualTo(MMCR.id("item"));
        assertThat(operation.slot()).isEqualTo(2);
        assertThat(operation.amount()).isEqualTo(4L);
        assertThat(operation.insert()).isFalse();
    }

    @Test
    void snapshot_slots_cannot_be_mutated_after_capture() {
        var slots = new ArrayList<>(List.of(
                new AsyncCapabilitySnapshot.ResourceSlot<>(ItemResource.EMPTY, 0L, 64L)));
        AsyncCapabilitySnapshot.Resource<ItemResource> snapshot = new AsyncCapabilitySnapshot.Resource<>(
                MMCR.id("item"), slots);
        slots.clear();

        assertThat(snapshot.slots()).hasSize(1);
    }
}
