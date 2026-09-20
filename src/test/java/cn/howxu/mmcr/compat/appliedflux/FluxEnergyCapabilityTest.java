package cn.howxu.mmcr.compat.appliedflux;

import cn.howxu.mmcr.api.capability.async.AsyncCapabilityOperation;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityPlanner;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityRequest;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import cn.howxu.mmcr.api.capability.facet.EnergyOutputAdmissionFacet;
import cn.howxu.mmcr.api.capability.facet.OperationFacet;
import cn.howxu.mmcr.api.capability.facet.PersistenceFacet;
import cn.howxu.mmcr.api.capability.facet.PresentationFacet;
import cn.howxu.mmcr.api.capability.facet.RecipeEnergyPrefetchFacet;
import cn.howxu.mmcr.api.capability.facet.ScalarFacet;
import cn.howxu.mmcr.api.capability.facet.SyncFacet;
import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.api.capability.facet.ValueFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.plan.CapabilityRequests;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.compat.appliedflux.loaded.capability.FluxEnergyInputCapability;
import cn.howxu.mmcr.compat.appliedflux.loaded.capability.FluxEnergyOutputCapability;
import cn.howxu.mmcr.compat.appliedflux.loaded.storage.FluxEnergyBuffer;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies local-only Flux capability planning contracts.
 *
 * @author howxu <dev@howxu.cn>
 */
class FluxEnergyCapabilityTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrapCapabilities();
    }

    @Test
    void outputAdmissionCountsExistingPendingEnergyBeforeNewOutput() {
        FluxEnergyBuffer pending = new FluxEnergyBuffer();
        try (Transaction transaction = Transaction.openRoot()) {
            pending.insert(40L, transaction);
            transaction.commit();
        }
        FluxEnergyOutputCapability capability = new FluxEnergyOutputCapability(pending);
        capability.refreshAdmissionBudget(100L);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(capability.outputCapacity(reservations)).isEqualTo(60L);
        EnergyOutputAdmissionFacet.OutputPlan partial = capability.planOutput(70L, reservations, true);
        assertThat(partial.accepted()).isZero();
        assertThat(partial.operation()).isNull();
        EnergyOutputAdmissionFacet.OutputPlan plan = capability.planOutput(60L, reservations, true);
        assertThat(plan.accepted()).isEqualTo(60L);
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
            transaction.commit();
        }

        assertThat(pending.amount()).isEqualTo(100L);
        assertThat(capability.outputCapacity(new PlanningReservations())).isZero();
    }

    @Test
    void asyncSnapshotPlansAndCommitsLocalInputOnly() throws Exception {
        FluxEnergyBuffer buffer = new FluxEnergyBuffer();
        try (Transaction transaction = Transaction.openRoot()) {
            buffer.insert(20L, transaction);
            transaction.commit();
        }
        FluxEnergyInputCapability capability = new FluxEnergyInputCapability(buffer, "test-input");
        AsyncPlanningFacet facet = capability.facet(AsyncPlanningFacet.class).orElseThrow();
        AsyncCapabilitySnapshot.Scalar snapshot = (AsyncCapabilitySnapshot.Scalar) invoke(facet,
                "captureSnapshotOnServerThread");
        AsyncCapabilityPlanner planner = (AsyncCapabilityPlanner) invoke(facet, "workerPlannerOnServerThread");
        AsyncCapabilityOperation operation = planner.plan(snapshot, new AsyncCapabilityRequest.Scalar(
                capability.type().id(), 1L, 7L, false)).orElseThrow();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(commit(facet, operation, transaction).success()).isTrue();
            transaction.commit();
        }
        assertThat(snapshot.amount()).isEqualTo(20L);
        assertThat(buffer.amount()).isEqualTo(13L);
    }

    @Test
    void inputOperationLeavesLocalEnergyUntouchedWhenTheRequestIsMissing() {
        FluxEnergyBuffer buffer = new FluxEnergyBuffer();
        try (Transaction transaction = Transaction.openRoot()) {
            buffer.insert(10L, transaction);
            transaction.commit();
        }
        FluxEnergyInputCapability capability = new FluxEnergyInputCapability(buffer, "test-input");

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(capability.prepareOperation(new CapabilityRequests.ValueRequest(capability.type(), IOType.INPUT,
                    1L, 11L, false)).commit(transaction).success()).isFalse();
            transaction.commit();
        }

        assertThat(buffer.amount()).isEqualTo(10L);
    }

    @Test
    void injectedPrefetchMarksLocalEnergyReservedOnlyAfterTransactionCommit() {
        FluxEnergyBuffer buffer = new FluxEnergyBuffer();
        FluxEnergyInputCapability capability = new FluxEnergyInputCapability(buffer, "test-input",
                requested -> Optional.of(transaction -> CapabilityResult.successful()));
        var plan = capability.planPrefetch(25L).orElseThrow();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
        }
        assertThat(buffer.amount()).isZero();
        assertThat(buffer.reserved()).isZero();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
            transaction.commit();
        }
        assertThat(buffer.amount()).isEqualTo(25L);
        assertThat(buffer.reserved()).isEqualTo(25L);
    }

    @Test
    void prefetchDoesNotCallTheBridgeWhenTheLocalBufferCannotFitTheFullAmount() {
        FluxEnergyBuffer buffer = new FluxEnergyBuffer();
        buffer.setAmount(Long.MAX_VALUE - 1L);
        AtomicLong bridgeCalls = new AtomicLong();
        FluxEnergyInputCapability capability = new FluxEnergyInputCapability(buffer, "test-input",
                requested -> Optional.of(transaction -> {
                    bridgeCalls.incrementAndGet();
                    return CapabilityResult.successful();
                }));
        var plan = capability.planPrefetch(2L).orElseThrow();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isFalse();
            transaction.commit();
        }

        assertThat(bridgeCalls).hasValue(0L);
        assertThat(buffer.amount()).isEqualTo(Long.MAX_VALUE - 1L);
        assertThat(buffer.reserved()).isZero();
    }

    @Test
    void asyncOutputUsesAdmissionBudgetAndRollsBackBudgetWithPendingEnergy() throws Exception {
        FluxEnergyOutputCapability capability = new FluxEnergyOutputCapability(new FluxEnergyBuffer());
        capability.refreshAdmissionBudget(12L);
        AsyncPlanningFacet facet = capability.facet(AsyncPlanningFacet.class).orElseThrow();
        AsyncCapabilitySnapshot.Scalar snapshot = (AsyncCapabilitySnapshot.Scalar) invoke(facet,
                "captureSnapshotOnServerThread");
        AsyncCapabilityPlanner planner = (AsyncCapabilityPlanner) invoke(facet, "workerPlannerOnServerThread");
        AsyncCapabilityOperation operation = planner.plan(snapshot, new AsyncCapabilityRequest.Scalar(
                capability.type().id(), 1L, 12L, true)).orElseThrow();

        assertThat(snapshot.amount()).isZero();
        assertThat(snapshot.capacity()).isEqualTo(12L);
        assertThat(snapshot.transferLimit()).isEqualTo(12L);
        try (Transaction ignored = Transaction.openRoot()) {
            assertThat(commit(facet, operation, ignored).success()).isTrue();
            assertThat(capability.admissionBudget()).isZero();
            assertThat(capability.storage().amount()).isEqualTo(12L);
        }
        assertThat(capability.admissionBudget()).isEqualTo(12L);
        assertThat(capability.storage().amount()).isZero();
    }

    @Test
    void outputLoadKeepsPendingEnergyAndResetsAdmissionBudget() throws Exception {
        FluxEnergyOutputCapability source = new FluxEnergyOutputCapability(new FluxEnergyBuffer());
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(source.prepareOperation(new CapabilityRequests.ValueRequest(source.type(), IOType.OUTPUT,
                    1L, 35L, true)).commit(transaction).success()).isTrue();
            transaction.commit();
        }
        source.refreshAdmissionBudget(100L);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                HolderLookup.Provider.create(Stream.empty()));
        source.save(output);
        FluxEnergyOutputCapability restored = new FluxEnergyOutputCapability(new FluxEnergyBuffer());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, HolderLookup.Provider.create(Stream.empty()),
                output.buildResult()));

        assertThat(restored.storage().amount()).isEqualTo(35L);
        assertThat(restored.admissionBudget()).isZero();
        assertThat(restored.outputCapacity(new PlanningReservations())).isZero();
        AsyncPlanningFacet facet = restored.facet(AsyncPlanningFacet.class).orElseThrow();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(commit(facet, new AsyncCapabilityOperation.Scalar(restored.type().id(), 1L, true), transaction)
                    .success()).isFalse();
            transaction.commit();
        }
        assertThat(restored.storage().amount()).isEqualTo(35L);
    }

    @Test
    void capabilitiesDeclareEveryImplementedFacetWithoutTransferExposure() {
        FluxEnergyInputCapability input = new FluxEnergyInputCapability(new FluxEnergyBuffer(), "test-input");
        FluxEnergyOutputCapability output = new FluxEnergyOutputCapability(new FluxEnergyBuffer());

        assertThat(input.view().facets()).containsExactlyInAnyOrder(ScalarFacet.class, ValueFacet.class,
                OperationFacet.class, PresentationFacet.class, PersistenceFacet.class, SyncFacet.class,
                AsyncPlanningFacet.class, RecipeEnergyPrefetchFacet.class);
        assertThat(output.view().facets()).containsExactlyInAnyOrder(ScalarFacet.class, ValueFacet.class,
                OperationFacet.class, PresentationFacet.class, PersistenceFacet.class, SyncFacet.class,
                AsyncPlanningFacet.class, EnergyOutputAdmissionFacet.class);
        assertThat(input.facet(RecipeEnergyPrefetchFacet.class)).isPresent();
        assertThat(output.facet(EnergyOutputAdmissionFacet.class)).isPresent();
        assertThat(input.facet(TransferFacet.class)).isEmpty();
        assertThat(output.facet(TransferFacet.class)).isEmpty();
    }

    private static Object invoke(AsyncPlanningFacet facet, String name) throws Exception {
        Method method = AsyncPlanningFacet.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(facet);
    }

    private static CapabilityResult commit(AsyncPlanningFacet facet, AsyncCapabilityOperation operation,
                                           TransactionContext transaction) throws Exception {
        Method method = AsyncPlanningFacet.class.getDeclaredMethod("commitOnServerThread",
                AsyncCapabilityOperation.class, TransactionContext.class);
        method.setAccessible(true);
        return (CapabilityResult) method.invoke(facet, operation, transaction);
    }
}
