package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.AsyncOutputResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.FluidResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AsyncOutputService;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.ItemResourceStorage;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.api.capability.plan.PlanningContext;
import cn.howxu.mmcr.api.recipe.ItemOutput;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.internal.capability.ItemBusCapability;
import cn.howxu.mmcr.internal.recipe.OutputResourceStorage.OutputPlan;
import cn.howxu.mmcr.internal.recipe.RequirementPlanner;
import cn.howxu.mmcr.util.IOType;
import cn.howxu.mmcr.test.TestBootstrap;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the transient async AE2 output service and its zero-slot resource view.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2AsyncOutputServiceTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
        TestBootstrap.bootstrapCapabilities();
        if (!ae2KeyTypesAreInitialized()) initializeAE2KeyTypes();
    }

    @Test
    void serviceIsEmptyWhenNothingSubmitted() {
        AsyncOutputService service = new AsyncOutputService();

        assertThat(service.isEmpty()).isTrue();
    }

    @Test
    void submitAndDrainRemovesAcceptedAmounts() {
        AsyncOutputService service = new AsyncOutputService();
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 64L);

        service.submit(AEItemKey.of(Items.IRON_INGOT), 8L);
        boolean drained = service.drainTo(network, source(), 4);

        assertThat(drained).isTrue();
        assertThat(service.isEmpty()).isTrue();
        assertThat(network.amount(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(8L);
    }

    @ParameterizedTest
    @ValueSource(longs = {4_095L, 4_096L, 4_097L, 2_147_487_743L})
    void committedItemsDrainDirectlyWithoutTransferEnergy(long amount) {
        AEKey key = AEItemKey.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(key, amount);
        AsyncOutputService service = new AsyncOutputService();
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, ItemResourceStorage.adapter(), service, IActionSource.empty(), () -> {});
        var plan = storage.planOutput(ItemResource.of(Items.IRON_INGOT), amount,
                new PlanningReservations(), true);

        assertThat(plan.accepted()).isEqualTo(amount);
        assertThat(plan.operation()).isNotNull();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
            transaction.commit();
        }
        assertThat(network.amount(key)).isZero();
        assertThat(service.isEmpty()).isFalse();

        assertThat(service.drainTo(network, IActionSource.empty(), 1)).isTrue();
        assertThat(network.amount(key)).isEqualTo(amount);
        assertThat(service.isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(longs = {4_095L, 4_096L, 4_097L, 2_147_487_743L})
    void committedFluidsDrainDirectlyWithoutTransferEnergy(long amount) {
        AEKey key = AEFluidKey.of(Fluids.WATER);
        FakeMEStorage network = new FakeMEStorage(key, amount);
        AsyncOutputService service = new AsyncOutputService();
        AsyncOutputResourceStorage<FluidResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, FluidResourceStorage.adapter(), service, IActionSource.empty(), () -> {});
        var plan = storage.planOutput(FluidResource.of(Fluids.WATER), amount,
                new PlanningReservations(), true);

        assertThat(plan.accepted()).isEqualTo(amount);
        assertThat(plan.operation()).isNotNull();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
            transaction.commit();
        }
        assertThat(network.amount(key)).isZero();
        assertThat(service.isEmpty()).isFalse();

        assertThat(service.drainTo(network, IActionSource.empty(), 1)).isTrue();
        assertThat(network.amount(key)).isEqualTo(amount);
        assertThat(service.isEmpty()).isTrue();
    }

    @Test
    void partialDrainRetainsUnacceptedAmountsInTheTransientAccumulator() {
        AsyncOutputService service = new AsyncOutputService();
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);
        network.capFirstModulation(3L);

        service.submit(AEItemKey.of(Items.IRON_INGOT), 8L);
        boolean drained = service.drainTo(network, source(), 4);

        assertThat(drained).isFalse();
        assertThat(service.isEmpty()).isFalse();
        assertThat(network.amount(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(3L);

        service.drainTo(network, source(), 4);
        assertThat(service.isEmpty()).isTrue();
        assertThat(network.amount(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(8L);
    }

    @Test
    void concurrentSubmitIsRetainedWhileDrainUpdatesTheQueue() throws Exception {
        AEKey key = AEItemKey.of(Items.IRON_INGOT);
        AsyncOutputService service = new AsyncOutputService();
        FakeMEStorage network = new FakeMEStorage(key, 64L);
        network.capFirstModulation(3L);
        network.blockNextModulation();
        service.submit(key, 8L);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread drain = new Thread(() -> {
            try {
                service.drainTo(network, source(), 1);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        drain.start();

        try {
            assertThat(network.modulationStarted.await(5L, TimeUnit.SECONDS)).isTrue();
            service.submit(key, 4L);
        } finally {
            network.releaseModulation.countDown();
        }

        drain.join(5_000L);
        assertThat(drain.isAlive()).isFalse();
        assertThat(failure.get()).isNull();
        assertThat(service.isEmpty()).isFalse();

        service.drainTo(network, source(), 1);

        assertThat(network.amount(key)).isEqualTo(12L);
    }

    @Test
    void submitIgnoresNullKeysAndNonPositiveAmounts() {
        AsyncOutputService service = new AsyncOutputService();
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);

        service.submit(null, 4L);
        service.submit(AEItemKey.of(Items.IRON_INGOT), 0L);
        service.submit(AEItemKey.of(Items.IRON_INGOT), -1L);

        assertThat(service.isEmpty()).isTrue();
        assertThat(service.drainTo(network, source(), 1)).isFalse();
    }

    @Test
    void clearRemovesAllPendingWork() {
        AsyncOutputService service = new AsyncOutputService();

        service.submit(AEItemKey.of(Items.IRON_INGOT), 4L);
        service.submit(AEItemKey.of(Items.GOLD_INGOT), 2L);
        service.clear();

        assertThat(service.isEmpty()).isTrue();
    }

    @Test
    void resourceStorageReportsZeroSizeAndNoSlots() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, ItemResourceStorage.adapter(), new AsyncOutputService(), source(),
                () -> {
                });

        assertThat(storage.size()).isZero();
        assertThat(storage.amount(0)).isZero();
        assertThat(storage.resource(0)).isNull();
    }

    @Test
    void copperRecipeCannotReclaimCommittedCapacityAcrossPlanningRounds() {
        AEKey copper = AEItemKey.of(Items.COPPER_INGOT);
        FakeMEStorage network = new FakeMEStorage(copper, 114_514L);
        AsyncOutputService service = new AsyncOutputService();
        var storage = items(network, service);
        var capabilities = List.of(new ItemBusCapability(null, storage, IOType.OUTPUT, false));
        var requirements = List.of(ItemRequirement.output(new ItemOutput(Items.COPPER_INGOT, 114_514)));
        RequirementPlanner planner = new RequirementPlanner();
        var first = planner.plan(List.copyOf(requirements), List.copyOf(capabilities), new PlanningContext(1L, 0));
        var stale = planner.plan(List.copyOf(requirements), List.copyOf(capabilities), new PlanningContext(1L, 0));

        assertThat(first.successful()).isTrue();
        assertThat(stale.successful()).isTrue();
        assertThat(first.plan().commit()).isTrue();
        assertThat(stale.plan().commit()).isFalse();
        assertThat(planner.plan(List.copyOf(requirements), List.copyOf(capabilities),
                new PlanningContext(1L, 0)).successful()).isFalse();
        assertThat(network.amount(copper)).isZero();

        assertThat(service.drainTo(network, IActionSource.empty(), 1)).isTrue();
        assertThat(network.extract(copper, 114_514L, Actionable.MODULATE, IActionSource.empty()))
                .isEqualTo(114_514L);
        assertThat(planner.plan(List.copyOf(requirements), List.copyOf(capabilities),
                new PlanningContext(1L, 0)).successful()).isTrue();
    }

    @Test
    void repeatedOutputsUseTheRemainingBudgetRatherThanSubtractingFromTheRequest() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 12L);
        var storage = items(network, new AsyncOutputService());
        PlanningReservations reservations = new PlanningReservations();
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);

        assertThat(storage.planOutput(iron, 4L, reservations, false).accepted()).isEqualTo(4L);
        assertThat(storage.planOutput(iron, 4L, reservations, false).accepted()).isEqualTo(4L);
        assertThat(storage.planOutput(iron, 8L, reservations, false).accepted()).isEqualTo(4L);
        assertThat(storage.planOutput(iron, 1L, reservations, false).accepted()).isZero();
        assertThat(storage.outputCapacity(iron)).isEqualTo(12L);
    }

    @Test
    void interfacesShareQueuedCapacityAndPartialDrainsReleaseOnlyDeliveredAmounts() {
        AEKey key = AEItemKey.of(Items.IRON_INGOT);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(key, 16L);
        AsyncOutputService firstService = new AsyncOutputService();
        var first = items(network, firstService);
        var second = items(network, new AsyncOutputService());
        commit(first.planOutput(iron, 10L, new PlanningReservations(), true));

        assertThat(second.outputCapacity(iron)).isEqualTo(6L);
        network.capFirstModulation(3L);
        assertThat(firstService.drainTo(network, IActionSource.empty(), 1)).isFalse();
        assertThat(second.outputCapacity(iron)).isEqualTo(6L);
        assertThat(network.extract(key, 3L, Actionable.MODULATE, IActionSource.empty())).isEqualTo(3L);
        assertThat(second.outputCapacity(iron)).isEqualTo(9L);
        firstService.clear();
        assertThat(second.outputCapacity(iron)).isEqualTo(16L);
    }

    @Test
    void staleIndependentPlansCannotBothCommitInsideOneRootTransaction() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 10L);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        var first = items(network, new AsyncOutputService());
        var second = items(network, new AsyncOutputService());
        var firstPlan = first.planOutput(iron, 6L, new PlanningReservations(), true);
        var secondPlan = second.planOutput(iron, 6L, new PlanningReservations(), true);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(firstPlan.operation().commit(transaction).success()).isTrue();
            assertThat(secondPlan.operation().commit(transaction).success()).isFalse();
            assertThat(second.outputCapacity(iron)).isEqualTo(4L);
        }
        assertThat(second.outputCapacity(iron)).isEqualTo(10L);
    }

    @Test
    void independentConcurrentCommitsCannotClaimTheSameNetworkCapacity() throws Exception {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 10L);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        var first = items(network, new AsyncOutputService());
        var second = items(network, new AsyncOutputService());
        List<OutputPlan> plans = List.of(first.planOutput(iron, 6L, new PlanningReservations(), true),
                second.planOutput(iron, 6L, new PlanningReservations(), true));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> workers = plans.stream().map(plan -> new Thread(() -> {
            try {
                if (!start.await(5L, TimeUnit.SECONDS)) throw new AssertionError("Commit start timed out");
                try (Transaction transaction = Transaction.openRoot()) {
                    if (plan.operation().commit(transaction).success()) {
                        transaction.commit();
                        committed.incrementAndGet();
                    }
                }
            } catch (Throwable exception) {
                failure.compareAndSet(null, exception);
            }
        })).toList();
        workers.forEach(Thread::start);
        start.countDown();
        for (Thread worker : workers) {
            worker.join(5_000L);
            assertThat(worker.isAlive()).isFalse();
        }

        assertThat(failure.get()).isNull();
        assertThat(committed.get()).isEqualTo(1);
        assertThat(first.outputCapacity(iron)).isEqualTo(4L);
    }

    @Test
    void networkCapacityChangesAreRecheckedBeforeStagingOutput() {
        AEKey key = AEItemKey.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(key, 10L);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        AsyncOutputService service = new AsyncOutputService();
        var storage = items(network, service);
        var plan = storage.planOutput(iron, 8L, new PlanningReservations(), true);
        network.insert(key, 4L, Actionable.MODULATE, IActionSource.empty());

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isFalse();
        }
        assertThat(service.isEmpty()).isTrue();
        assertThat(storage.outputCapacity(iron)).isEqualTo(6L);
    }

    @Test
    void childAbortReleasesOnlyItsOwnCapacityAndRootCommitDoesNotCountTwice() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 10L);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        var storage = items(network, new AsyncOutputService());
        var parentPlan = storage.planOutput(iron, 4L, new PlanningReservations(), true);
        var childPlan = storage.planOutput(iron, 3L, new PlanningReservations(), true);

        try (Transaction root = Transaction.openRoot()) {
            assertThat(parentPlan.operation().commit(root).success()).isTrue();
            try (Transaction child = Transaction.open(root)) {
                assertThat(childPlan.operation().commit(child).success()).isTrue();
                assertThat(storage.outputCapacity(iron)).isEqualTo(3L);
            }
            assertThat(storage.outputCapacity(iron)).isEqualTo(6L);
            root.commit();
        }
        assertThat(storage.outputCapacity(iron)).isEqualTo(6L);
    }

    @Test
    void networkChangeMovesQueuedReservationsAndRejectsPlansForTheOldNetwork() {
        AEKey key = AEItemKey.of(Items.IRON_INGOT);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage oldNetwork = new FakeMEStorage(key, 16L);
        FakeMEStorage newNetwork = new FakeMEStorage(key, 16L);
        AtomicReference<MEStorage> current = new AtomicReference<>(oldNetwork);
        AsyncOutputService service = new AsyncOutputService();
        var storage = new AsyncOutputResourceStorage<>(current::get, ItemResourceStorage.adapter(),
                service, IActionSource.empty(), () -> {});
        var stale = storage.planOutput(iron, 1L, new PlanningReservations(), true);
        commit(storage.planOutput(iron, 8L, new PlanningReservations(), true));

        current.set(newNetwork);
        assertThat(storage.outputCapacity(iron)).isEqualTo(8L);
        assertThat(items(oldNetwork, new AsyncOutputService()).outputCapacity(iron)).isEqualTo(16L);
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(stale.operation().commit(transaction).success()).isFalse();
        }
        assertThat(service.drainTo(newNetwork, IActionSource.empty(), 1)).isTrue();
        assertThat(oldNetwork.amount(key)).isZero();
        assertThat(newNetwork.amount(key)).isEqualTo(8L);

        commit(storage.planOutput(iron, 4L, new PlanningReservations(), true));
        current.set(null);
        assertThat(storage.outputCapacity(iron)).isZero();
        assertThat(items(newNetwork, new AsyncOutputService()).outputCapacity(iron)).isEqualTo(8L);
        service.clear();
    }

    @Test
    void queuedFluidCapacityIsSharedAcrossViewsAndReleasedOnClear() {
        FakeMEStorage network = new FakeMEStorage(AEFluidKey.of(Fluids.WATER), 8_000L);
        AsyncOutputService service = new AsyncOutputService();
        var first = new AsyncOutputResourceStorage<>(() -> network, FluidResourceStorage.adapter(),
                service, IActionSource.empty(), () -> {});
        var second = new AsyncOutputResourceStorage<>(() -> network, FluidResourceStorage.adapter(),
                new AsyncOutputService(), IActionSource.empty(), () -> {});
        FluidResource water = FluidResource.of(Fluids.WATER);
        commit(first.planOutput(water, 6_000L, new PlanningReservations(), true));

        assertThat(second.outputCapacity(water)).isEqualTo(2_000L);
        service.clear();
        assertThat(second.outputCapacity(water)).isEqualTo(8_000L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleClearInvalidatesStagedAdmissionsBeforeCommitOrRollback(boolean commit) {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 10L);
        AsyncOutputService service = new AsyncOutputService();
        var storage = items(network, service);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        var plan = storage.planOutput(iron, 6L, new PlanningReservations(), true);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
            service.clear();
            assertThat(storage.outputCapacity(iron)).isEqualTo(10L);
            if (commit) transaction.commit();
        }
        assertThat(service.isEmpty()).isTrue();
        assertThat(storage.outputCapacity(iron)).isEqualTo(10L);
    }

    @Test
    void oldTransactionCallbacksDoNotConsumeNewAdmissionsAfterLifecycleClear() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 10L);
        AsyncOutputService service = new AsyncOutputService();
        var storage = items(network, service);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        var old = storage.planOutput(iron, 6L, new PlanningReservations(), true);

        try (Transaction root = Transaction.openRoot()) {
            assertThat(old.operation().commit(root).success()).isTrue();
            service.clear();
            var fresh = storage.planOutput(iron, 4L, new PlanningReservations(), true);
            try (Transaction child = Transaction.open(root)) {
                assertThat(fresh.operation().commit(child).success()).isTrue();
                child.commit();
            }
            root.commit();
        }
        assertThat(storage.outputCapacity(iron)).isEqualTo(6L);
        assertThat(service.drainTo(network, IActionSource.empty(), 1)).isTrue();
        assertThat(network.amount(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(4L);
    }

    @Test
    void migratingQueuesBeyondLongMaxDoesNotCorruptEitherNetworksBudget() {
        AEKey key = AEItemKey.of(Items.IRON_INGOT);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        long amount = Long.MAX_VALUE / 2L + 1L;
        FakeMEStorage oldNetwork = new FakeMEStorage(key, Long.MAX_VALUE);
        FakeMEStorage targetNetwork = new FakeMEStorage(key, Long.MAX_VALUE);
        AtomicReference<MEStorage> current = new AtomicReference<>(oldNetwork);
        AsyncOutputService movingService = new AsyncOutputService();
        AsyncOutputService residentService = new AsyncOutputService();
        var moving = new AsyncOutputResourceStorage<>(current::get, ItemResourceStorage.adapter(),
                movingService, IActionSource.empty(), () -> {});
        var resident = items(targetNetwork, residentService);
        commit(moving.planOutput(iron, amount, new PlanningReservations(), true));
        commit(resident.planOutput(iron, amount, new PlanningReservations(), true));

        current.set(targetNetwork);
        assertThat(moving.outputCapacity(iron)).isZero();
        assertThat(items(oldNetwork, new AsyncOutputService()).outputCapacity(iron)).isEqualTo(Long.MAX_VALUE);
        movingService.clear();
        assertThat(resident.outputCapacity(iron)).isEqualTo(Long.MAX_VALUE - amount);
        residentService.clear();
        assertThat(resident.outputCapacity(iron)).isEqualTo(Long.MAX_VALUE);
    }

    private static AsyncOutputResourceStorage<ItemResource> items(MEStorage network, AsyncOutputService service) {
        return new AsyncOutputResourceStorage<>(() -> network, ItemResourceStorage.adapter(), service,
                IActionSource.empty(), () -> {});
    }

    private static void commit(OutputPlan plan) {
        assertThat(plan.operation()).isNotNull();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
            transaction.commit();
        }
    }

    @Test
    void resourceStorageOutputCapacityUsesNetworkSimulation() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 12L);
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, ItemResourceStorage.adapter(), new AsyncOutputService(), source(),
                () -> {
                });

        assertThat(storage.outputCapacity(ItemResource.of(Items.IRON_INGOT))).isEqualTo(12L);
    }

    @Test
    void resourceStoragePlansNetworkOutputWithReservationIdentity() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);
        AsyncOutputService service = new AsyncOutputService();
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, ItemResourceStorage.adapter(), service, source(), () -> {
                });
        PlanningReservations reservations = new PlanningReservations();
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);

        var plan = storage.planOutput(iron, 4L, reservations, true);

        assertThat(plan.accepted()).isEqualTo(4L);
        assertThat(plan.operation()).isNotNull();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
            transaction.commit();
        }
        assertThat(service.isEmpty()).isFalse();
        assertThat(storage.reservationIdentity()).isSameAs(service);
    }

    @Test
    void resourceStorageDoesNotSubmitWhenRootTransactionAborts() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);
        AsyncOutputService service = new AsyncOutputService();
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, ItemResourceStorage.adapter(), service, source(), () -> {
                });
        var plan = storage.planOutput(ItemResource.of(Items.IRON_INGOT), 4L,
                new PlanningReservations(), true);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation()).isNotNull();
            assertThat(plan.operation().commit(transaction).success()).isTrue();
        }

        assertThat(service.isEmpty()).isTrue();
    }

    @Test
    void resourceStorageDoesNotSubmitWhenParentTransactionAbortsAfterChildCommit() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);
        AsyncOutputService service = new AsyncOutputService();
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, ItemResourceStorage.adapter(), service, source(), () -> {
                });
        var plan = storage.planOutput(ItemResource.of(Items.IRON_INGOT), 4L,
                new PlanningReservations(), true);

        try (Transaction root = Transaction.openRoot()) {
            try (Transaction child = Transaction.open(root)) {
                assertThat(plan.operation()).isNotNull();
                assertThat(plan.operation().commit(child).success()).isTrue();
                child.commit();
            }
        }

        assertThat(service.isEmpty()).isTrue();
        assertThat(storage.outputCapacity(ItemResource.of(Items.IRON_INGOT))).isEqualTo(8L);
    }

    @Test
    void resourceStoragePlanReturnsZeroWhenNetworkIsAbsent() {
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> null, ItemResourceStorage.adapter(), new AsyncOutputService(), source(),
                () -> {
                });

        var plan = storage.planOutput(ItemResource.of(Items.IRON_INGOT), 4L,
                new PlanningReservations(), true);

        assertThat(plan.accepted()).isZero();
        assertThat(plan.operation()).isNull();
    }

    @Test
    void resourceStoragePlanReturnsZeroWhenCapacityIsExhausted() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 0L);
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, ItemResourceStorage.adapter(), new AsyncOutputService(), source(),
                () -> {
                });

        var plan = storage.planOutput(ItemResource.of(Items.IRON_INGOT), 4L,
                new PlanningReservations(), true);

        assertThat(plan.accepted()).isZero();
        assertThat(plan.operation()).isNull();
    }

    @Test
    void resourceStorageOrdinaryInsertAndExtractReturnZero() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);
        AsyncOutputResourceStorage<ItemResource> storage = new AsyncOutputResourceStorage<>(
                () -> network, ItemResourceStorage.adapter(), new AsyncOutputService(), source(),
                () -> {
                });

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, ItemResource.of(Items.IRON_INGOT), 4L, transaction)).isZero();
            assertThat(storage.extract(0, ItemResource.of(Items.IRON_INGOT), 4L, transaction)).isZero();
            transaction.commit();
        }
    }

    private static IActionSource source() {
        IEnergyService energy = (IEnergyService) Proxy.newProxyInstance(
                IEnergyService.class.getClassLoader(), new Class<?>[]{IEnergyService.class},
                (_, method, _) -> method.getName().equals("extractAEPower") ? 1_000_000D
                        : defaultValue(method.getReturnType()));
        IGrid[] grid = new IGrid[1];
        IGridNode node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(), new Class<?>[]{IGridNode.class},
                (_, method, _) -> method.getName().equals("getGrid") ? grid[0]
                        : defaultValue(method.getReturnType()));
        grid[0] = (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(), new Class<?>[]{IGrid.class},
                (_, method, _) -> method.getName().equals("getEnergyService") ? energy
                        : defaultValue(method.getReturnType()));
        return IActionSource.ofMachine(() -> node);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static boolean ae2KeyTypesAreInitialized() {
        try {
            return !AEKeyTypes.getAll().isEmpty();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static void initializeAE2KeyTypes() {
        MappedRegistry<AEKeyType> registry = new MappedRegistry<>(AEKeyType.REGISTRY_KEY, Lifecycle.stable());
        AEKeyTypesInternal.setRegistry(registry);
        Registry.register(registry, AEKeyType.items().getId(), AEKeyType.items());
        Registry.register(registry, AEKeyType.fluids().getId(), AEKeyType.fluids());
        registry.freeze();
    }

    private static final class FakeMEStorage implements MEStorage {
        private final Map<AEKey, Long> amounts = new HashMap<>();
        private final Map<AEKey, Long> capacities;
        private final CountDownLatch modulationStarted = new CountDownLatch(1);
        private final CountDownLatch releaseModulation = new CountDownLatch(1);
        private long firstModulationCap = Long.MAX_VALUE;
        private boolean blockModulation;

        private FakeMEStorage(AEKey key, long capacity) {
            this.capacities = new HashMap<>();
            capacities.put(key, capacity);
        }

        private void capFirstModulation(long limit) {
            firstModulationCap = limit;
        }

        private void blockNextModulation() {
            blockModulation = true;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            long current = amounts.getOrDefault(key, 0L);
            long capacity = capacities.getOrDefault(key, 0L);
            long available = Math.max(0L, capacity - current);
            if (mode == Actionable.MODULATE && current == 0L) available = Math.min(available, firstModulationCap);
            if (mode == Actionable.MODULATE && blockModulation) {
                blockModulation = false;
                modulationStarted.countDown();
                try {
                    if (!releaseModulation.await(5L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent submit");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for concurrent submit", exception);
                }
            }
            long inserted = Math.min(amount, available);
            if (mode == Actionable.MODULATE && inserted > 0L) {
                amounts.put(key, current + inserted);
            }
            return inserted;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            long current = amounts.getOrDefault(key, 0L);
            long extracted = Math.min(amount, current);
            if (mode == Actionable.MODULATE && extracted > 0L) {
                amounts.put(key, current - extracted);
            }
            return extracted;
        }

        @Override
        public Component getDescription() {
            return Component.literal("test");
        }

        private long amount(AEKey key) {
            return amounts.getOrDefault(key, 0L);
        }
    }
}
