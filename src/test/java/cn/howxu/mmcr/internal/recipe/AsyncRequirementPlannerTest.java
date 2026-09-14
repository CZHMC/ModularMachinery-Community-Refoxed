package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityOperation;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityPlanner;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityRequest;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.api.capability.async.AsyncResourceAction;
import cn.howxu.mmcr.api.capability.async.AsyncResourceValue;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.storage.LongValueStorage;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.recipe.CraftingContext;
import cn.howxu.mmcr.api.recipe.requirement.EnergyRequirement;
import cn.howxu.mmcr.internal.capability.EnergyHatchCapability;
import cn.howxu.mmcr.internal.capability.FluidHatchCapability;
import cn.howxu.mmcr.internal.capability.ItemBusCapability;
import cn.howxu.mmcr.internal.capability.NativeAsyncResourceValues;
import cn.howxu.mmcr.internal.storage.BulkItemStorage;
import cn.howxu.mmcr.internal.storage.LongFluidStorage;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import java.util.List;
import java.util.Optional;
import java.lang.reflect.Method;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies worker-safe requirement planning from native capability descriptors.
 *
 * @author howxu <dev@howxu.cn>
 */
class AsyncRequirementPlannerTest {
    private static final AsyncResourceValue IRON = new AsyncResourceValue(MMCR.id("iron_ingot"), "{}");
    private static final AsyncResourceValue WATER = new AsyncResourceValue(MMCR.id("water"), "{}");

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void supported_resource_requirements_plan_to_logical_operations_from_value_descriptors() {
        AsyncRequirementPlanner.PlanResult result = new AsyncRequirementPlanner().plan(List.of(
                resourceRequirement(4, MMCR.id("item"), IRON, 2L, false),
                resourceRequirement(7, MMCR.id("fluid"), WATER, 1_000L, false)), List.of(
                resourceCapability(MMCR.id("item"), IRON, 4L, 64L),
                resourceCapability(MMCR.id("fluid"), WATER, 1_000L, 4_000L)));

        assertThat(result.operations()).hasSize(2);
        assertThat(result.operations()).allSatisfy(operation ->
                assertThat(operation.operation()).isInstanceOf(AsyncCapabilityOperation.Group.class));
        assertThat(result.mainThreadRequirements()).isEmpty();
    }

    @Test
    void unsupported_requirement_falls_back_by_original_index_without_discarding_other_operations() {
        AsyncRequirementPlanner.PlanResult result = new AsyncRequirementPlanner().plan(List.of(
                resourceRequirement(3, MMCR.id("item"), IRON, 2L, false),
                new AsyncRequirementPlanner.Requirement(11, 10L, List.of(
                        new AsyncCapabilityRequest.Scalar(MMCR.id("heat"), 1L, 10L, false)))), List.of(
                resourceCapability(MMCR.id("item"), IRON, 4L, 64L)));

        assertThat(result.operations()).singleElement().satisfies(operation ->
                assertThat(operation.requirementIndex()).isEqualTo(3));
        assertThat(result.mainThreadRequirements()).containsExactly(11);
    }

    @Test
    void native_capabilities_expose_live_async_planning_facets() {
        assertThat(new ItemBusCapability(new BulkItemStorage(64L, null), IOType.INPUT)
                .facet(AsyncPlanningFacet.class)).isPresent();
        assertThat(new FluidHatchCapability(new LongFluidStorage(1_000L, null), IOType.INPUT)
                .facet(AsyncPlanningFacet.class)).isPresent();
        assertThat(new EnergyHatchCapability(new LongValueStorage(1_000L, 1_000L, null), IOType.INPUT)
                .facet(AsyncPlanningFacet.class)).isPresent();
    }

    @Test
    void crafting_context_returns_a_worker_safe_descriptor_with_ordered_fallback_indexes() {
        AsyncRequirementPlanner.PreparedPlan prepared = new CraftingContext(new CapabilitySnapshot(List.of()))
                .planAsync(List.of(new EnergyRequirement(4L)), 1L);

        assertThat(prepared.plan().operations()).isEmpty();
        assertThat(prepared.plan().mainThreadRequirements()).containsExactly(0);
    }

    @Test
    void resource_group_commit_rolls_back_its_prefix_when_a_later_operation_is_rejected() throws Exception {
        BulkItemStorage storage = new BulkItemStorage(64L, null);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        try (Transaction transaction = Transaction.openRoot()) {
            storage.insert(0, iron, 2L, transaction);
            transaction.commit();
        }
        ItemBusCapability capability = new ItemBusCapability(storage, IOType.INPUT);
        AsyncCapabilityOperation group = new AsyncCapabilityOperation.Group(List.of(
                new AsyncCapabilityOperation.Resource(capability.type().id(), 0,
                        NativeAsyncResourceValues.item(iron), 1L, false),
                new AsyncCapabilityOperation.Resource(capability.type().id(), 0,
                        new AsyncResourceValue(MMCR.id("gold_ingot"), "{}"), 1L, false)));

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(commit(capability, group, transaction).success()).isFalse();
            transaction.commit();
        }

        assertThat(storage.amount(0)).isEqualTo(2L);
        assertThat(storage.resource(0)).isEqualTo(iron);
    }

    private static AsyncRequirementPlanner.Requirement resourceRequirement(int index,
                                                                            net.minecraft.resources.Identifier capabilityId,
                                                                            AsyncResourceValue resource,
                                                                            long amount, boolean insert) {
        return new AsyncRequirementPlanner.Requirement(index, amount, List.of(
                new AsyncCapabilityRequest.Resource(capabilityId, 1L,
                        List.of(new AsyncResourceAction(resource, amount, insert)))));
    }

    private static AsyncRequirementPlanner.Capability resourceCapability(net.minecraft.resources.Identifier capabilityId,
                                                                          AsyncResourceValue resource,
                                                                          long amount, long capacity) {
        return new AsyncRequirementPlanner.Capability(new AsyncCapabilityPlanner.Resource(capabilityId),
                new AsyncCapabilitySnapshot.Resource(capabilityId, List.of(
                        new AsyncCapabilitySnapshot.ResourceSlot(Optional.of(resource), amount, capacity))));
    }

    private static CapabilityResult commit(ItemBusCapability capability, AsyncCapabilityOperation operation,
                                           TransactionContext transaction) throws Exception {
        Method method = ItemBusCapability.class.getDeclaredMethod("commitAsync", AsyncCapabilityOperation.class,
                TransactionContext.class);
        method.setAccessible(true);
        return (CapabilityResult) method.invoke(capability, operation, transaction);
    }
}
