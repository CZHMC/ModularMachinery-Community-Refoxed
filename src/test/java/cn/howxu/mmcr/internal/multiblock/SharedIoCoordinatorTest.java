package cn.howxu.mmcr.internal.multiblock;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityPlanner;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityRequest;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.internal.async.MachineAsyncCoordinator;
import cn.howxu.mmcr.internal.recipe.AsyncRequirementPlanner;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SharedIoCoordinatorTest {
    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B = new BlockPos(4, 64, 0);
    private static final BlockPos C = new BlockPos(8, 64, 0);

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void domain_tick_workset_reserves_a_shared_snapshot_in_lane_order() {
        var capabilityId = MMCR.id("energy");
        AsyncCapabilitySnapshot snapshot = new AsyncCapabilitySnapshot.Scalar(capabilityId, 10L, 10L, 10L);
        AsyncRequirementPlanner.Capability capability = new AsyncRequirementPlanner.Capability(
                new AsyncCapabilityPlanner.Scalar(capabilityId), snapshot, Set.of(IOType.INPUT));
        AsyncRequirementPlanner.Requirement requirement = new AsyncRequirementPlanner.Requirement(0, 6L,
                IOType.INPUT, List.of(new AsyncCapabilityRequest.Scalar(capabilityId, 1L, 6L, false)));
        AsyncRequirementPlanner.PreparedPlan plan = new AsyncRequirementPlanner.PreparedPlan(
                List.of(requirement), List.of(capability), List.of());

        List<AsyncRequirementPlanner.PlanResult> results = SharedIoCoordinator.planTickWorksetForTesting(
                List.of(plan, plan));

        assertThat(results.get(0).operations()).hasSize(1);
        assertThat(results.get(0).mainThreadRequirements()).isEmpty();
        assertThat(results.get(1).operations()).isEmpty();
        assertThat(results.get(1).mainThreadRequirements()).containsExactly(0);
    }

    @Test
    void cancelling_a_controller_discards_its_pending_domain_tick_work() throws Exception {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A, B);
        ServerLevel level = allocate(ServerLevel.class);
        AtomicInteger discarded = new AtomicInteger();
        AsyncRequirementPlanner.PreparedPlan plan = new AsyncRequirementPlanner.PreparedPlan(
                List.of(), List.of(), List.of());

        coordinator.enqueueTickWork(level, domain,
                new MachineAsyncCoordinator.TaskKey(A, 1L), plan, ignored -> { }, discarded::incrementAndGet);
        coordinator.enqueueTickWork(level, domain,
                new MachineAsyncCoordinator.TaskKey(B, 1L), plan, ignored -> { }, () -> { });

        coordinator.cancel(A);

        assertThat(discarded).hasValue(1);
    }

    @Test
    void startRequestsUseRotatingOrderAndMayReceivePartialParallelism() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A, B);
        List<String> committed = new ArrayList<>();

        coordinator.enqueue(start(domain, A, 17L, 8,
                maximum -> Math.min(maximum, 8), granted -> committed.add("A:" + granted), () -> true, () -> 17L));
        coordinator.enqueue(start(domain, B, 21L, 8,
                maximum -> Math.min(maximum, 2), granted -> committed.add("B:" + granted), () -> true, () -> 21L));

        coordinator.resolve(domain);

        assertThat(committed).containsExactly("A:8", "B:2");
        assertThat(coordinator.nextStartLane(domain.id()))
                .isEqualTo(new SharedIoCoordinator.LaneKey(B, "base"));
    }

    @Test
    void invalid_request_runs_its_validator_once_per_resolution() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicInteger validations = new AtomicInteger();

        coordinator.enqueue(tick(domain, A, 1L, () -> true,
                () -> {
                    validations.incrementAndGet();
                    return false;
                }, () -> 1L));

        coordinator.resolve(domain);

        assertThat(validations).hasValue(1);
    }

    @Test
    void finish_spawned_start_runs_its_validator_once_per_resolution() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicInteger validations = new AtomicInteger();
        coordinator.enqueue(finish(domain, A, 1L, () -> {
            coordinator.enqueue(start(domain, A, 1L, 1L, ignored -> 1L, ignored -> { },
                    () -> {
                        validations.incrementAndGet();
                        return true;
                    }, () -> 1L));
            return true;
        }, () -> true, () -> 1L));

        coordinator.resolve(domain);

        assertThat(validations).hasValue(1);
    }

    @Test
    void round_robin_moves_past_the_last_base_lane_of_the_same_controller() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        List<String> committed = new ArrayList<>();

        coordinator.enqueue(new SharedIoCoordinator.StartRequest(domain,
                new SharedIoCoordinator.LaneKey(A, "base"), 1L, 0L, 1,
                ignored -> 1, ignored -> committed.add("base"), () -> true, () -> 1L, () -> 0L));
        coordinator.resolve(domain);
        committed.clear();
        coordinator.enqueue(new SharedIoCoordinator.StartRequest(domain,
                new SharedIoCoordinator.LaneKey(A, "base"), 1L, 0L, 1,
                ignored -> 1, ignored -> committed.add("base"), () -> true, () -> 1L, () -> 0L));
        coordinator.enqueue(new SharedIoCoordinator.StartRequest(domain,
                new SharedIoCoordinator.LaneKey(A, "factory-0"), 1L, 0L, 1,
                ignored -> 1, ignored -> committed.add("factory-0"), () -> true, () -> 1L, () -> 0L));

        coordinator.resolve(domain);

        assertThat(committed).containsExactly("factory-0", "base");
    }

    @Test
    void round_robin_rotates_between_all_lanes_of_the_same_controller() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        List<String> committed = new ArrayList<>();

        coordinator.enqueue(new SharedIoCoordinator.StartRequest(domain,
                new SharedIoCoordinator.LaneKey(A, "factory-0"), 1L, 0L, 1,
                ignored -> 1, ignored -> committed.add("factory-0"), () -> true, () -> 1L, () -> 0L));
        coordinator.resolve(domain);
        committed.clear();
        for (String laneId : List.of("base", "factory-0", "factory-1")) {
            coordinator.enqueue(new SharedIoCoordinator.StartRequest(domain,
                    new SharedIoCoordinator.LaneKey(A, laneId), 1L, 0L, 1,
                    ignored -> 1, ignored -> committed.add(laneId), () -> true, () -> 1L, () -> 0L));
        }

        coordinator.resolve(domain);

        assertThat(committed).containsExactly("factory-1", "base", "factory-0");
    }

    @Test
    void start_tick_and_finish_requests_use_independent_cursors() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A, B, C);
        List<String> committed = new ArrayList<>();

        coordinator.enqueue(start(domain, A, 1L, 1,
                ignored -> 1, ignored -> committed.add("initial-start"), () -> true, () -> 1L));
        coordinator.enqueue(tick(domain, B, 1L,
                () -> { committed.add("initial-tick"); return true; }, () -> true, () -> 1L));
        coordinator.enqueue(finish(domain, C, 1L,
                () -> { committed.add("initial-finish"); return true; }, () -> true, () -> 1L));
        coordinator.resolve(domain);
        committed.clear();

        enqueueAllRequestTypes(coordinator, domain, committed);
        coordinator.resolve(domain);

        assertThat(committed).containsExactly(
                "start:B", "start:C", "start:A",
                "tick:C", "tick:A", "tick:B",
                "finish:A", "finish:B", "finish:C");
    }

    @Test
    void cancelling_a_controller_discards_its_start_tick_and_finish_requests() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A, B);
        List<String> committed = new ArrayList<>();

        coordinator.enqueue(start(domain, A, 1L, 1, ignored -> 1, ignored -> committed.add("start"), () -> true, () -> 1L));
        coordinator.enqueue(tick(domain, A, 1L, () -> {
            committed.add("tick");
            return true;
        }, () -> true, () -> 1L));
        coordinator.enqueue(finish(domain, A, 1L, () -> {
            committed.add("finish");
            return true;
        }, () -> true, () -> 1L));
        coordinator.enqueue(tick(domain, B, 1L, () -> {
            committed.add("other-controller");
            return true;
        }, () -> true, () -> 1L));

        coordinator.cancel(A);
        coordinator.resolve(domain);

        assertThat(committed).containsExactly("other-controller");
    }

    @Test
    void finite_shared_energy_rotates_to_the_lane_that_can_finish_the_next_tick() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A, B);
        AtomicInteger energy = new AtomicInteger(20);
        List<String> advanced = new ArrayList<>();

        enqueueEnergyTick(coordinator, domain, A, energy, advanced);
        enqueueEnergyTick(coordinator, domain, B, energy, advanced);
        coordinator.resolve(domain);
        energy.addAndGet(15);
        enqueueEnergyTick(coordinator, domain, A, energy, advanced);
        enqueueEnergyTick(coordinator, domain, B, energy, advanced);
        coordinator.resolve(domain);

        assertThat(advanced).containsExactly("A", "B");
    }

    @Test
    void finish_commit_can_install_a_replacement_start_without_running_its_tick() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        SharedIoCoordinator.LaneKey lane = new SharedIoCoordinator.LaneKey(A, "base");
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger ticks = new AtomicInteger();

        coordinator.enqueue(new SharedIoCoordinator.FinishRequest(domain, lane, 1L, 0L, () -> {
            coordinator.enqueue(new SharedIoCoordinator.StartRequest(domain, lane, 1L, 0L, 1,
                    ignored -> 1, ignored -> starts.incrementAndGet(), () -> true, () -> 1L, () -> 0L));
            coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, lane, 1L, 0L,
                    () -> { ticks.incrementAndGet(); return true; }, () -> true, () -> 1L, () -> 0L));
            return true;
        }, () -> true, () -> 1L, () -> 0L));

        coordinator.resolve(domain);

        assertThat(starts).hasValue(1);
        assertThat(ticks).hasValue(0);
    }

    @Test
    void tick_commit_can_enqueue_finish_for_the_same_domain_pass() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        SharedIoCoordinator.LaneKey lane = new SharedIoCoordinator.LaneKey(A, "base");
        List<String> committed = new ArrayList<>();

        coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, lane, 1L, 0L, () -> {
            committed.add("tick");
            coordinator.enqueue(new SharedIoCoordinator.FinishRequest(domain, lane, 1L, 0L,
                    () -> { committed.add("finish"); return true; }, () -> true, () -> 1L, () -> 0L));
            return true;
        }, () -> true, () -> 1L, () -> 0L));

        coordinator.resolve(domain);

        assertThat(committed).containsExactly("tick", "finish");
    }

    @Test
    void staleStructureAndStateVersionsNeverInvokeTransactions() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicLong structureVersion = new AtomicLong(1L);
        AtomicLong stateVersion = new AtomicLong(1L);
        AtomicBoolean invoked = new AtomicBoolean();

        coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, lane(A), 1L, 1L,
                () -> {
                    invoked.set(true);
                    return true;
                }, () -> true, structureVersion::get, stateVersion::get));
        structureVersion.incrementAndGet();

        coordinator.resolve(domain);

        assertThat(invoked).isFalse();
    }

    @Test
    void valid_request_is_checked_once_immediately_before_commit() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicInteger validations = new AtomicInteger();

        coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, lane(A), 1L, 0L,
                () -> true, () -> {
                    validations.incrementAndGet();
                    return true;
                }, () -> 1L, () -> 0L));

        coordinator.resolve(domain);

        assertThat(validations).hasValue(1);
    }

    @Test
    void catalog_change_before_shared_start_commit_never_runs_runtime_or_resource_transaction() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicLong catalogVersion = new AtomicLong(1L);
        AtomicInteger runtimeStarts = new AtomicInteger();
        AtomicInteger extractedInputs = new AtomicInteger();

        coordinator.enqueue(new SharedIoCoordinator.StartRequest(domain, lane(A), 1L, 0L, 1,
                ignored -> {
                    runtimeStarts.incrementAndGet();
                    extractedInputs.incrementAndGet();
                    return 1;
                }, ignored -> { }, () -> true, () -> 1L, () -> 0L,
                1L, catalogVersion::get, () -> { }));
        catalogVersion.set(2L);

        coordinator.resolve(domain);

        assertThat(runtimeStarts.get()).isZero();
        assertThat(extractedInputs.get()).isZero();
    }

    @Test
    void catalog_change_before_shared_tick_or_finish_commit_never_runs_transactions() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicLong catalogVersion = new AtomicLong(1L);
        AtomicInteger committed = new AtomicInteger();

        coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, lane(A), 1L, 0L,
                () -> {
                    committed.incrementAndGet();
                    return true;
                }, () -> true, () -> 1L, () -> 0L, 1L, catalogVersion::get, () -> { }));
        coordinator.enqueue(new SharedIoCoordinator.FinishRequest(domain, lane(A), 1L, 0L,
                () -> {
                    committed.incrementAndGet();
                    return true;
                }, () -> true, () -> 1L, () -> 0L, 1L, catalogVersion::get, () -> { }));
        catalogVersion.incrementAndGet();

        coordinator.resolve(domain);

        assertThat(committed).hasValue(0);
    }

    @Test
    void stateVersionInvalidationAlsoDiscardsPendingRequests() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicLong stateVersion = new AtomicLong(1L);
        AtomicBoolean invoked = new AtomicBoolean();

        coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, lane(A), 1L, 1L,
                () -> {
                    invoked.set(true);
                    return true;
                }, () -> true, () -> 1L, stateVersion::get));
        stateVersion.incrementAndGet();

        coordinator.resolve(domain);

        assertThat(invoked).isFalse();
    }

    @Test
    void finishRequestCanInstallAReplacementStartInTheSameDomainPass() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicInteger starts = new AtomicInteger();

        coordinator.enqueue(finish(domain, A, 1L, () -> {
            coordinator.enqueue(start(domain, A, 1L, 1,
                    ignored -> 1, ignored -> starts.incrementAndGet(), () -> true, () -> 1L));
            return true;
        }, () -> true, () -> 1L));

        coordinator.resolve(domain);

        assertThat(starts).hasValue(1);
    }

    @Test
    void resolve_reports_completed_work_even_when_a_replacement_request_remains_pending() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        coordinator.enqueue(finish(domain, A, 1L, () -> {
            coordinator.enqueue(start(domain, A, 1L, 1, ignored -> 0, ignored -> { }, () -> true, () -> 1L));
            return true;
        }, () -> true, () -> 1L));

        assertThat(coordinator.resolve(domain)).isEqualTo(1);
    }

    @Test
    void invalidValidatorPreventsAStaleLaneFromCommittingAfterEarlierLaneChangesState() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A, B);
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicBoolean staleInvoked = new AtomicBoolean();
        List<String> committed = new ArrayList<>();

        coordinator.enqueue(tick(domain, A, 1L,
                () -> {
                    committed.add("A");
                    valid.set(false);
                    return true;
                }, valid::get, () -> 1L));
        coordinator.enqueue(tick(domain, B, 1L,
                () -> {
                    staleInvoked.set(true);
                    committed.add("B");
                    return true;
                }, valid::get, () -> 1L));

        coordinator.resolve(domain);

        assertThat(committed).containsExactly("A");
        assertThat(staleInvoked).isFalse();
    }

    @Test
    void resolver_discards_a_partitioned_request_after_its_lifecycle_epoch_changes() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A, B);
        AtomicLong lifecycleEpoch = new AtomicLong(1L);
        AtomicBoolean staleInvoked = new AtomicBoolean();

        coordinator.enqueue(tick(domain, A, 1L, () -> {
            lifecycleEpoch.incrementAndGet();
            return true;
        }, () -> true, () -> 1L));
        long requestEpoch = lifecycleEpoch.get();
        coordinator.enqueue(tick(domain, B, 1L, () -> {
            staleInvoked.set(true);
            return true;
        }, () -> lifecycleEpoch.get() == requestEpoch, () -> 1L));

        coordinator.resolve(domain);

        assertThat(staleInvoked).isFalse();
    }

    @Test
    void validity_filtering_preserves_stage_order_when_requests_are_partitioned() {
        SharedIoCoordinator coordinator = new SharedIoCoordinator();
        StructureClaimRegistry.ResourceDomain domain = domain(A);
        AtomicBoolean invalid = new AtomicBoolean(false);
        List<String> committed = new ArrayList<>();

        coordinator.enqueue(start(domain, A, 1L, 1,
                ignored -> 1, ignored -> committed.add("start"), () -> true, () -> 1L));
        coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, lane(A), 1L, 0L,
                () -> {
                    invalid.set(true);
                    return true;
                }, () -> false, () -> 1L, () -> 0L));
        coordinator.enqueue(finish(domain, A, 1L,
                () -> {
                    committed.add("finish");
                    return true;
                }, () -> true, () -> 1L));

        coordinator.resolve(domain);

        assertThat(committed).containsExactly("start", "finish");
        assertThat(invalid).isFalse();
    }

    private static StructureClaimRegistry.ResourceDomain domain(BlockPos... positions) {
        return new StructureClaimRegistry.ResourceDomain(1L, 1L, Set.of(positions));
    }

    private static SharedIoCoordinator.LaneKey lane(BlockPos position) {
        return new SharedIoCoordinator.LaneKey(position, "base");
    }

    private static void enqueueAllRequestTypes(SharedIoCoordinator coordinator,
                                                StructureClaimRegistry.ResourceDomain domain,
                                                List<String> committed) {
        for (BlockPos pos : List.of(A, B, C)) {
            String lane = pos.equals(A) ? "A" : pos.equals(B) ? "B" : "C";
            SharedIoCoordinator.LaneKey laneKey = lane(pos);
            coordinator.enqueue(start(domain, pos, 1L, 1,
                    ignored -> 1, ignored -> committed.add("start:" + lane), () -> true, () -> 1L));
            coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, laneKey, 1L, 0L,
                    () -> { committed.add("tick:" + lane); return true; }, () -> true, () -> 1L, () -> 0L));
            coordinator.enqueue(new SharedIoCoordinator.FinishRequest(domain, laneKey, 1L, 0L,
                    () -> { committed.add("finish:" + lane); return true; }, () -> true, () -> 1L, () -> 0L));
        }
    }

    private static void enqueueEnergyTick(SharedIoCoordinator coordinator,
                                          StructureClaimRegistry.ResourceDomain domain,
                                          BlockPos position, AtomicInteger energy, List<String> advanced) {
        String lane = position.equals(A) ? "A" : "B";
        coordinator.enqueue(new SharedIoCoordinator.TickRequest(domain, lane(position), 1L, 0L,
                () -> {
                    if (energy.get() < 15) return false;
                    energy.addAndGet(-15);
                    advanced.add(lane);
                    return true;
                }, () -> true, () -> 1L, () -> 0L));
    }

    private static SharedIoCoordinator.StartRequest start(StructureClaimRegistry.ResourceDomain domain,
                                                           BlockPos position, long structureVersion,
                                                            long maximumParallelism,
                                                            LongUnaryOperator transaction,
                                                            LongConsumer committer,
                                                           BooleanSupplier validator,
                                                           LongSupplier structureVersionSupplier) {
        return new SharedIoCoordinator.StartRequest(domain, lane(position), structureVersion, 0L,
                maximumParallelism, transaction, committer, validator, structureVersionSupplier, () -> 0L);
    }

    private static SharedIoCoordinator.TickRequest tick(StructureClaimRegistry.ResourceDomain domain,
                                                         BlockPos position, long structureVersion,
                                                         BooleanSupplier transaction,
                                                         BooleanSupplier validator,
                                                         LongSupplier structureVersionSupplier) {
        return new SharedIoCoordinator.TickRequest(domain, lane(position), structureVersion, 0L,
                transaction, validator, structureVersionSupplier, () -> 0L);
    }

    private static SharedIoCoordinator.FinishRequest finish(StructureClaimRegistry.ResourceDomain domain,
                                                             BlockPos position, long structureVersion,
                                                             BooleanSupplier transaction,
                                                             BooleanSupplier validator,
                                                             LongSupplier structureVersionSupplier) {
        return new SharedIoCoordinator.FinishRequest(domain, lane(position), structureVersion, 0L,
                transaction, validator, structureVersionSupplier, () -> 0L);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
    }
}
