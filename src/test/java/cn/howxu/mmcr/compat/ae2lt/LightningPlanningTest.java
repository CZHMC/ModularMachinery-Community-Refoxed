package cn.howxu.mmcr.compat.ae2lt;

import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.api.capability.facet.InputRoutingFacet;
import cn.howxu.mmcr.api.capability.plan.OutputPolicy;
import cn.howxu.mmcr.api.capability.plan.PlanningContext;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.api.compat.ae2lt.LightningTier;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandlerRegistry;
import cn.howxu.mmcr.compat.ae2lt.loaded.LightningCapability;
import cn.howxu.mmcr.internal.recipe.RequirementPlanner;
import cn.howxu.mmcr.internal.storage.LongResourceStorage;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises lightning execution, shared reservations and rollback rather than capacity constants.
 * @author howxu <dev@howxu.cn> */
class LightningPlanningTest {
    private static final LightningTier HIGH = LightningTier.HIGH_VOLTAGE;
    private static final LightningTier EXTREME = LightningTier.EXTREME_HIGH_VOLTAGE;
    private RequirementHandlerRegistry.TestScope requirements;
    private OutputRegistry.TestScope outputs;

    @BeforeEach
    void setup() throws Exception {
        TestBootstrap.bootstrapCapabilities();
        requirements = RequirementHandlerRegistry.openTestScope();
        outputs = OutputRegistry.openTestScope();
        LightningBridgeBootstrap.installForTesting(LightningBridgeBootstrap.selectForTesting(true));
        LightningRecipeTypes.register();
    }

    @AfterEach
    void cleanup() {
        outputs.close();
        requirements.close();
        LightningBridgeBootstrap.resetForTesting();
    }

    @Test
    void differentTiersCannotSubstitute() {
        var storage = storage(100);
        storage.setContents(0, EXTREME, 80);
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH, 40)),
                List.of(new LightningCapability(storage, IOType.INPUT)), new PlanningContext(1, 0));
        assertThat(result.successful()).isFalse();
        assertThat(storage.amount(0)).isEqualTo(80);
    }

    @Test
    void simulationAndRollbackPreserveBalances() {
        var storage = storage(100);
        storage.setContents(0, HIGH, 80);
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH, 30)),
                List.of(new LightningCapability(storage, IOType.INPUT)), new PlanningContext(1, 0));
        assertThat(result.successful()).isTrue();
        assertThat(storage.amount(0)).isEqualTo(80);
        try (Transaction root = Transaction.openRoot()) {
            try (Transaction nested = Transaction.open(root)) {
                assertThat(result.plan().commit(nested)).isTrue();
                nested.commit();
            }
            assertThat(storage.amount(0)).isEqualTo(50);
        }
        assertThat(storage.amount(0)).isEqualTo(80);
        assertThat(result.plan().commit()).isTrue();
        assertThat(storage.amount(0)).isEqualTo(50);
    }

    @Test
    void sharedStorageReservationsCannotDoubleSpend() {
        var storage = storage(100);
        storage.setContents(0, HIGH, 60);
        List<MachineCapability> capabilities = List.of(new LightningCapability(storage, IOType.INPUT),
                new LightningCapability(new SharedView(storage), IOType.INPUT));
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH, 40),
                LightningRequirement.input(HIGH, 40)), capabilities, new PlanningContext(1, 0));
        assertThat(result.successful()).isFalse();
        assertThat(storage.amount(0)).isEqualTo(60);
    }

    @Test
    void changedStorageMakesCommitFailWithoutPartialConsumption() {
        var storage = new LongResourceStorage<>(LightningTier.class, 2, 100, ignored -> false, null);
        storage.setContents(0, HIGH, 40);
        storage.setContents(1, HIGH, 40);
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH, 60)),
                List.of(new LightningCapability(storage, IOType.INPUT)), new PlanningContext(1, 0));
        assertThat(result.successful()).isTrue();
        storage.setContents(1, HIGH, 10);
        assertThat(result.plan().commit()).isFalse();
        assertThat(storage.amount(0)).isEqualTo(40);
        assertThat(storage.amount(1)).isEqualTo(10);
    }

    @Test
    void parallelAmountUsesLong() {
        var storage = storage(6_000_000_000L);
        storage.setContents(0, HIGH, 5_000_000_000L);
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH, 2_147_483_647L)),
                List.of(new LightningCapability(storage, IOType.INPUT)), new PlanningContext(2, 0));
        assertThat(result.successful()).isTrue();
        assertThat(result.plan().parallelism()).isEqualTo(2);
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(result.plan().commit(transaction)).isTrue();
            assertThat(storage.amount(0)).isEqualTo(705_032_706L);
        }
        assertThat(storage.amount(0)).isEqualTo(5_000_000_000L);
        assertThat(result.plan().commit()).isTrue();
        assertThat(storage.amount(0)).isEqualTo(705_032_706L);
    }

    @Test
    void isolatedScopeCannotBorrowLightning() {
        var first = storage(100);
        var second = storage(100);
        first.setContents(0, HIGH, 30);
        second.setContents(0, HIGH, 30);
        var firstPos = new BlockPos(1, 0, 0);
        var secondPos = new BlockPos(2, 0, 0);
        var routing = new InputRoutingFacet(1, List.of(new InputRoutingFacet.Source(0, firstPos),
                new InputRoutingFacet.Source(1, secondPos)), List.of(new InputRoutingFacet.Port(firstPos, true),
                new InputRoutingFacet.Port(secondPos, false)));
        var context = new PlanningContext(1, 0, false, new PlanningReservations(), Map.of(), routing, null, null);
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH, 50)),
                List.of(new LightningCapability(first, IOType.INPUT), new LightningCapability(second, IOType.INPUT)), context);
        assertThat(result.successful()).isFalse();
        assertThat(first.amount(0)).isEqualTo(30);
        assertThat(second.amount(0)).isEqualTo(30);
    }

    @Test
    void blockedRecipeWakesOnlyForMatchingTier() {
        var handler = new LightningRequirementHandler();
        var wakeup = handler.resourceWakeups(LightningRequirement.input(HIGH, 1)).getFirst();
        assertThat(wakeup.matcher().test(EXTREME)).isFalse();
        assertThat(wakeup.matcher().test(HIGH)).isTrue();
        assertThat(handler.overlaps(LightningRequirement.input(HIGH, 1),
                LightningRequirement.input(EXTREME, 1))).isFalse();
        assertThat(handler.overlaps(LightningRequirement.input(HIGH, 1),
                LightningRequirement.input(HIGH, 2))).isTrue();
    }

    @Test
    void outputMultiplierAndInsufficientCapacityPreservePolicy() {
        var handler = new LightningRequirementHandler();
        var output = handler.applyLevelModifiers(LightningRequirement.output(HIGH, 40, 1F), 1, 2);
        var storage = storage(50);
        var capability = new LightningCapability(storage, IOType.OUTPUT);
        var planner = new RequirementPlanner();
        assertThat(output.amount()).isEqualTo(80);
        assertThat(planner.plan(List.of(output), List.of(capability), new PlanningContext(1, 0)).successful())
                .isFalse();
        assertThat(storage.amount(0)).isZero();
        var partial = planner.plan(List.of(output), List.of(capability),
                new PlanningContext(1, 0, Map.of(0, OutputPolicy.ALLOW_PARTIAL)));
        assertThat(partial.successful()).isTrue();
        assertThat(partial.plan().commit()).isTrue();
        assertThat(storage.amount(0)).isEqualTo(50);
        assertThat(storage.resource(0)).isEqualTo(HIGH);
    }

    @Test
    void zeroOutputMultiplierDoesNotCreateLightning() {
        var output = new LightningRequirementHandler().applyLevelModifiers(
                LightningRequirement.output(HIGH, 40, 1F), 1, 0);
        var storage = storage(100);
        var result = new RequirementPlanner().plan(List.of(output),
                List.of(new LightningCapability(storage, IOType.OUTPUT)), new PlanningContext(1, 0));
        assertThat(result.successful()).isTrue();
        assertThat(result.plan().commit()).isTrue();
        assertThat(storage.amount(0)).isZero();
    }

    @Test
    void globalAmountModifierChangesConsumptionWithoutEnablingProbabilisticInput() {
        var input = new LightningRequirementHandler().applyModifiers(LightningRequirement.input(HIGH, 20), List.of(
                new RecipeModifier("", RecipeModifier.IOType.INPUT, 2F, RecipeModifier.Operation.MULTIPLY, false),
                new RecipeModifier("", RecipeModifier.IOType.INPUT, 0F, RecipeModifier.Operation.MULTIPLY, true),
                new RecipeModifier("item", RecipeModifier.IOType.INPUT, 10F, RecipeModifier.Operation.MULTIPLY, false)));
        var storage = storage(100);
        storage.setContents(0, HIGH, 80);
        var result = new RequirementPlanner().plan(List.of(input),
                List.of(new LightningCapability(storage, IOType.INPUT)), new PlanningContext(1, 0));
        assertThat(result.successful()).isTrue();
        assertThat(result.plan().commit()).isTrue();
        assertThat(storage.amount(0)).isEqualTo(40);
    }

    @Test
    void reservationsCannotReplaceOtherTierOrExposeAdjacentTransport() {
        var storage = storage(100);
        var reservations = new PlanningReservations();
        assertThat(reservations.reserveInsert(storage, 0, HIGH, 10)).isTrue();
        var output = LightningRequirement.output(EXTREME, 10, 1F);
        var capability = new LightningCapability(storage, IOType.OUTPUT);
        assertThat(capability.facet(TransferFacet.class)).isEmpty();
        assertThat(new RequirementPlanner().plan(List.of(output), List.of(capability),
                new PlanningContext(1, 0, false, reservations)).successful()).isFalse();
        assertThat(storage.amount(0)).isZero();
    }

    private static LongResourceStorage<LightningTier> storage(long capacity) {
        return new LongResourceStorage<>(LightningTier.class, 1, capacity, ignored -> false, null);
    }

    private record SharedView(ResourceStorage<LightningTier> delegate) implements ResourceStorage<LightningTier> {
        public Class<LightningTier> resourceType() { return LightningTier.class; }
        public Object reservationIdentity() { return delegate.reservationIdentity(); }
        public int size() { return delegate.size(); }
        public LightningTier resource(int slot) { return delegate.resource(slot); }
        public long amount(int slot) { return delegate.amount(slot); }
        public long capacity(int slot, LightningTier resource) { return delegate.capacity(slot, resource); }
        public boolean isValid(int slot, LightningTier resource) { return delegate.isValid(slot, resource); }
        public long insert(int slot, LightningTier resource, long amount, TransactionContext transaction) {
            return delegate.insert(slot, resource, amount, transaction);
        }
        public long extract(int slot, LightningTier resource, long amount, TransactionContext transaction) {
            return delegate.extract(slot, resource, amount, transaction);
        }
    }
}
