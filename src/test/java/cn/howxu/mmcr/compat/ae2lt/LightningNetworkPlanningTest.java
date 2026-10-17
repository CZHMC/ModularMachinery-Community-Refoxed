package cn.howxu.mmcr.compat.ae2lt;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.plan.PlanningContext;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandlerRegistry;
import cn.howxu.mmcr.compat.ae2lt.loaded.LightningCapability;
import cn.howxu.mmcr.compat.ae2lt.loaded.LightningNetworkResourceStorage;
import cn.howxu.mmcr.compat.ae2lt.loaded.LoadedLightningBridge;
import cn.howxu.mmcr.internal.recipe.RequirementPlanner;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.howxu.mmcr.api.compat.ae2lt.LightningTier.EXTREME_HIGH_VOLTAGE;
import static cn.howxu.mmcr.api.compat.ae2lt.LightningTier.HIGH_VOLTAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real network view configurations share reservations and a transactional extraction journal.
 * @author howxu <dev@howxu.cn> */
class LightningNetworkPlanningTest {
    private RequirementHandlerRegistry.TestScope requirements;
    private OutputRegistry.TestScope outputs;

    @BeforeEach
    void setup() throws Exception {
        TestBootstrap.bootstrapCapabilities();
        requirements = RequirementHandlerRegistry.openTestScope();
        outputs = OutputRegistry.openTestScope();
        LightningBridgeBootstrap.installForTesting(new LoadedLightningBridge());
        LightningRecipeTypes.register();
    }

    @AfterEach
    void cleanup() {
        outputs.close();
        requirements.close();
        LightningBridgeBootstrap.resetForTesting();
    }

    @Test
    void sameNetworkKeyAtDifferentSlotsCannotPlanEightyFromSixty() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        var first = view(network, high);
        var second = view(network, AEItemKey.of(Items.IRON_INGOT), high);
        List<MachineCapability> capabilities = List.of(new LightningCapability(first, IOType.INPUT),
                new LightningCapability(second, IOType.INPUT));
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH_VOLTAGE, 80L)),
                capabilities, new PlanningContext(1L, 0));
        assertThat(result.successful()).isFalse();
        assertThat(network.amount(high)).isEqualTo(60L);
        assertThat(network.modulateCalls).isZero();
    }

    @Test
    void twoFortyRequirementsAcrossDifferentConfigurationsCannotDoubleSpendSixty() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        var first = view(network, high);
        var second = view(network, AEItemKey.of(Items.IRON_INGOT), high);
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH_VOLTAGE, 40L),
                        LightningRequirement.input(HIGH_VOLTAGE, 40L)),
                List.of(new LightningCapability(first, IOType.INPUT), new LightningCapability(second, IOType.INPUT)),
                new PlanningContext(1L, 0));
        assertThat(result.successful()).isFalse();
        assertThat(network.amount(high)).isEqualTo(60L);
    }

    @Test
    void differentTiersAtTheSameLocalSlotCanBothBePlannedAndConsumed() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var extreme = LoadedLightningBridge.FAMILY.adapter().toKey(EXTREME_HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        network.setAmount(extreme, 60L);
        var first = view(network, high);
        var second = view(network, extreme);
        var result = new RequirementPlanner().plan(List.of(LightningRequirement.input(HIGH_VOLTAGE, 40L),
                        LightningRequirement.input(EXTREME_HIGH_VOLTAGE, 50L)),
                List.of(new LightningCapability(first, IOType.INPUT), new LightningCapability(second, IOType.INPUT)),
                new PlanningContext(1L, 0));
        assertThat(result.successful()).isTrue();
        assertThat(network.amount(high)).isEqualTo(60L);
        assertThat(network.amount(extreme)).isEqualTo(60L);
        assertThat(result.plan().commit()).isTrue();
        assertThat(network.amount(high)).isEqualTo(20L);
        assertThat(network.amount(extreme)).isEqualTo(10L);
        assertThat(network.modulateCalls).isEqualTo(2);
    }

    @Test
    void reservationCopiesKeepKeyAddressesAndIndependentBudgets() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        var first = view(network, high);
        var second = view(network, AEItemKey.of(Items.IRON_INGOT), high);
        var reservations = new PlanningReservations();
        assertThat(reservations.reserveExtract(first, 0, HIGH_VOLTAGE, 40L)).isTrue();
        var copy = reservations.copy();
        assertThat(copy.amount(second, 1)).isEqualTo(20L);
        assertThat(copy.reserveExtract(second, 1, HIGH_VOLTAGE, 21L)).isFalse();
        assertThat(copy.reserveExtract(second, 1, HIGH_VOLTAGE, 20L)).isTrue();
        assertThat(copy.amount(first, 0)).isZero();
        assertThat(reservations.amount(second, 1)).isEqualTo(20L);
        assertThat(network.amount(high)).isEqualTo(60L);
    }

    @Test
    void stagedExtractionSharesTheLiveSimulationBudgetAcrossViews() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        var first = view(network, high);
        var second = view(network, AEItemKey.of(Items.IRON_INGOT), high);
        try (var transaction = Transaction.openRoot()) {
            assertThat(first.extract(0, HIGH_VOLTAGE, 40L, transaction)).isEqualTo(40L);
            assertThat(second.extract(1, HIGH_VOLTAGE, 40L, transaction)).isEqualTo(20L);
            assertThat(first.extract(0, HIGH_VOLTAGE, 1L, transaction)).isZero();
            assertThat(network.amount(high)).isEqualTo(60L);
        }
        assertThat(network.amount(high)).isEqualTo(60L);
        assertThat(network.modulateCalls).isZero();
    }

    @Test
    void sharedJournalRestoresFiftyWhenSixtyWasStagedBeforeTheNetworkChanged() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        var first = view(network, high);
        var second = view(network, AEItemKey.of(Items.IRON_INGOT), high);
        assertThatThrownBy(() -> {
            try (var transaction = Transaction.openRoot()) {
                assertThat(first.extract(0, HIGH_VOLTAGE, 40L, transaction)).isEqualTo(40L);
                assertThat(second.extract(1, HIGH_VOLTAGE, 20L, transaction)).isEqualTo(20L);
                assertThat(network.amount(high)).isEqualTo(60L);
                network.setAmount(high, 50L);
                transaction.commit();
            }
        }).hasRootCauseMessage("Unable to complete AE2 network extraction");
        assertThat(network.amount(high)).isEqualTo(50L);
        assertThat(network.modulateCalls).isEqualTo(1);
        assertThat(network.restored).isEqualTo(50L);
        try (var transaction = Transaction.openRoot()) {
            assertThat(second.extract(1, HIGH_VOLTAGE, 10L, transaction)).isEqualTo(10L);
            transaction.commit();
        }
        assertThat(network.amount(high)).isEqualTo(40L);
    }

    @Test
    void failureOnAnotherTierRestoresAllEarlierKeysInTheSharedJournal() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var extreme = LoadedLightningBridge.FAMILY.adapter().toKey(EXTREME_HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        network.setAmount(extreme, 20L);
        var first = view(network, high);
        var second = view(network, extreme);
        assertThatThrownBy(() -> {
            try (var transaction = Transaction.openRoot()) {
                assertThat(first.extract(0, HIGH_VOLTAGE, 40L, transaction)).isEqualTo(40L);
                assertThat(second.extract(0, EXTREME_HIGH_VOLTAGE, 20L, transaction)).isEqualTo(20L);
                network.setAmount(extreme, 10L);
                transaction.commit();
            }
        }).hasRootCauseMessage("Unable to complete AE2 network extraction");
        assertThat(network.amount(high)).isEqualTo(60L);
        assertThat(network.amount(extreme)).isEqualTo(10L);
        assertThat(network.restored).isEqualTo(50L);
    }

    @Test
    void crossViewNestedCommitRollsBackWithRootAndSuccessfulRetryConsumesOnce() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        var first = view(network, high);
        var second = view(network, AEItemKey.of(Items.IRON_INGOT), high);
        for (boolean commit : new boolean[]{false, true}) {
            try (var root = Transaction.openRoot()) {
                assertThat(first.extract(0, HIGH_VOLTAGE, 40L, root)).isEqualTo(40L);
                try (var nested = Transaction.open(root)) {
                    assertThat(second.extract(1, HIGH_VOLTAGE, 20L, nested)).isEqualTo(20L);
                    nested.commit();
                }
                assertThat(network.amount(high)).isEqualTo(60L);
                if (commit) root.commit();
            }
            assertThat(network.amount(high)).isEqualTo(commit ? 0L : 60L);
            assertThat(network.modulateCalls).isEqualTo(commit ? 1 : 0);
        }
    }

    @Test
    void nestedRollbackReleasesOnlyThatViewsStagedExtraction() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var network = new TestNetwork();
        network.setAmount(high, 60L);
        var first = view(network, high);
        var second = view(network, AEItemKey.of(Items.IRON_INGOT), high);
        try (var root = Transaction.openRoot()) {
            assertThat(first.extract(0, HIGH_VOLTAGE, 40L, root)).isEqualTo(40L);
            try (var nested = Transaction.open(root)) {
                assertThat(second.extract(1, HIGH_VOLTAGE, 20L, nested)).isEqualTo(20L);
            }
            assertThat(second.extract(1, HIGH_VOLTAGE, 40L, root)).isEqualTo(20L);
            assertThat(network.amount(high)).isEqualTo(60L);
            root.commit();
        }
        assertThat(network.amount(high)).isZero();
        assertThat(network.modulateCalls).isEqualTo(1);
    }

    @Test
    void newConfigurationsAndNewNetworksDoNotRedirectAnAlreadyStagedExtraction() {
        var high = LoadedLightningBridge.FAMILY.adapter().toKey(HIGH_VOLTAGE);
        var originalNetwork = new TestNetwork();
        var replacementNetwork = new TestNetwork();
        originalNetwork.setAmount(high, 60L);
        replacementNetwork.setAmount(high, 80L);
        var original = view(originalNetwork, high);
        try (var transaction = Transaction.openRoot()) {
            assertThat(original.extract(0, HIGH_VOLTAGE, 40L, transaction)).isEqualTo(40L);
            var reconfigured = view(originalNetwork, AEItemKey.of(Items.IRON_INGOT), high);
            var rebound = view(replacementNetwork, high);
            assertThat(reconfigured.extract(1, HIGH_VOLTAGE, 20L, transaction)).isEqualTo(20L);
            assertThat(rebound.extract(0, HIGH_VOLTAGE, 10L, transaction)).isEqualTo(10L);
            transaction.commit();
        }
        assertThat(originalNetwork.amount(high)).isZero();
        assertThat(replacementNetwork.amount(high)).isEqualTo(70L);
        assertThat(originalNetwork.modulateCalls).isEqualTo(1);
        assertThat(replacementNetwork.modulateCalls).isEqualTo(1);
    }

    private static LightningNetworkResourceStorage view(TestNetwork network, AEKey... keys) {
        var view = new LightningNetworkResourceStorage(network, List.of(keys));
        for (var key : keys) view.onStackChange(key, network.amount(key));
        return view;
    }

    /** @author howxu <dev@howxu.cn> */
    private static final class TestNetwork implements MEStorage {
        private final Map<AEKey, Long> balances = new LinkedHashMap<>();
        private int modulateCalls;
        private long restored;

        private void setAmount(AEKey key, long amount) {
            balances.put(key, amount);
        }

        private long amount(AEKey key) {
            return balances.getOrDefault(key, 0L);
        }

        @Override
        public long extract(AEKey key, long requested, Actionable mode, IActionSource source) {
            long extracted = Math.min(requested, amount(key));
            if (mode == Actionable.MODULATE) {
                modulateCalls++;
                balances.put(key, amount(key) - extracted);
            }
            return extracted;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.MODULATE) {
                restored += amount;
                balances.merge(key, amount, Math::addExact);
            }
            return amount;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            balances.forEach(out::add);
        }

        @Override
        public Component getDescription() {
            return Component.empty();
        }
    }
}
