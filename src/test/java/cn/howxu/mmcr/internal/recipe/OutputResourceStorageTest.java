package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.internal.recipe.OutputResourceStorage.OutputPlan;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies output planning separates virtual reservations from committed storage.
 *
 * @author howxu <dev@howxu.cn>
 */
class OutputResourceStorageTest {
    @Test
    void materialized_plan_returns_a_capability_operation() {
        FakeOutputStorage storage = new FakeOutputStorage(4L);

        OutputPlan plan = storage.planOutput("iron", 3L, new PlanningReservations(), true);

        assertThat(plan.accepted()).isEqualTo(3L);
        assertThat(plan.operation()).isNotNull();
        assertThat(plan.operation().commit(null)).isEqualTo(CapabilityResult.successful());
        assertThat(storage.amount(0)).isEqualTo(3L);
    }

    @Test
    void zero_capacity_plan_accepts_nothing() {
        FakeOutputStorage storage = new FakeOutputStorage(0L);

        OutputPlan plan = storage.planOutput("iron", 3L, new PlanningReservations(), true);

        assertThat(plan.accepted()).isZero();
        assertThat(plan.operation()).isNull();
    }

    @Test
    void unmaterialized_plan_reserves_capacity_without_changing_committed_storage() {
        FakeOutputStorage storage = new FakeOutputStorage(4L);
        PlanningReservations reservations = new PlanningReservations();

        OutputPlan plan = storage.planOutput("iron", 3L, reservations, false);

        assertThat(plan.accepted()).isEqualTo(3L);
        assertThat(plan.operation()).isNull();
        assertThat(storage.amount(0)).isZero();
        assertThat(reservations.outputAvailable(storage.network, "iron", 4L)).isEqualTo(1L);
    }

    private static final class FakeOutputStorage implements OutputResourceStorage<String> {
        private final Object network = new Object();
        private final long capacity;
        private long committed;

        private FakeOutputStorage(long capacity) {
            this.capacity = capacity;
        }

        @Override
        public long outputCapacity(String resource) {
            return capacity - committed;
        }

        @Override
        public OutputPlan planOutput(String resource, long amount, PlanningReservations reservations,
                                     boolean materialize) {
            long accepted = Math.min(amount, reservations.outputAvailable(network, resource, outputCapacity(resource)));
            if (accepted <= 0L || !reservations.reserveOutput(network, resource, accepted)) {
                return new OutputPlan(0L, null);
            }
            CapabilityOperation operation = materialize
                    ? transaction -> {
                        committed += accepted;
                        return CapabilityResult.successful();
                    }
                    : null;
            return new OutputPlan(accepted, operation);
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
            return committed == 0L ? null : "iron";
        }

        @Override
        public long amount(int slot) {
            return committed;
        }

        @Override
        public long capacity(int slot, String resource) {
            return capacity;
        }

        @Override
        public boolean isValid(int slot, String resource) {
            return "iron".equals(resource);
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
