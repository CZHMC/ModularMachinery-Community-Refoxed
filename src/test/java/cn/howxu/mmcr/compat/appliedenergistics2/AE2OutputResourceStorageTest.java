package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.helpers.externalstorage.GenericStackInv;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.FluidResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.ItemResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.OutputResourceStorage;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies normal AE2 output storage prefers the network and keeps a bounded local cache.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2OutputResourceStorageTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void insertCommitsToTheNetworkOnlyAfterRootCommit() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        OutputResourceStorage<ItemResource> storage = itemStorage(inventory(1), network);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 8L, transaction)).isEqualTo(8L);
            assertThat(network.amount(AEItemKey.of(iron))).isZero();
            transaction.commit();
        }

        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(8L);
    }

    @Test
    void networkShortfallIsStoredInTheLocalCache() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 3L);
        GenericStackInv cache = inventory(1);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, network);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 8L, transaction)).isEqualTo(8L);
            assertThat(network.amount(AEItemKey.of(iron))).isZero();
            assertThat(storage.amount(0)).isEqualTo(5L);
            transaction.commit();
        }

        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(3L);
        assertThat(storage.amount(0)).isEqualTo(5L);
    }

    @Test
    void rootCommitStoresNetworkModulationShortfallLocally() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(1);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        network.setModulationLimit(3L);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, network);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 8L, transaction)).isEqualTo(8L);
            assertThat(cache.getStack(0)).isNull();
            transaction.commit();
        }

        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(3L);
        assertThat(storage.amount(0)).isEqualTo(5L);
        assertThat(network.calls()).extracting(FakeMEStorage.InsertCall::mode)
                .containsExactly(Actionable.SIMULATE, Actionable.SIMULATE, Actionable.MODULATE);
        assertThat(network.calls()).extracting(FakeMEStorage.InsertCall::amount)
                .containsExactly(8L, 8L, 8L);
        assertThat(network.calls()).allSatisfy(call -> assertThat(call.source()).isNotNull());
    }

    @Test
    void rootCommitRejectsAnUncompensableNetworkShortfallBeforeModulation() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(1);
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 63L));
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        network.setModulationLimit(3L);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, network);

        assertThatThrownBy(() -> {
            try (Transaction transaction = Transaction.openRoot()) {
                storage.insert(0, iron, 8L, transaction);
                transaction.commit();
            }
        }).hasRootCauseMessage("Unable to retain AE2 output network shortfall");

        assertThat(network.amount(AEItemKey.of(iron))).isZero();
        assertThat(storage.amount(0)).isEqualTo(63L);
        assertThat(network.calls()).extracting(FakeMEStorage.InsertCall::mode)
                .contains(Actionable.MODULATE);
        assertThat(network.extractedAmount()).isEqualTo(3L);
    }

    @Test
    void failedRootCommitClearsTheNetworkPendingState() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(1);
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 64L));
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        network.setModulationLimit(3L);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, network);

        assertThatThrownBy(() -> {
            try (Transaction transaction = Transaction.openRoot()) {
                storage.insert(0, iron, 8L, transaction);
                transaction.commit();
            }
        }).hasRootCauseMessage("Unable to retain AE2 output network shortfall");

        assertThat(storage.outputCapacity(iron)).isEqualTo(64L);
    }

    @Test
    void disconnectedNetworkUsesOnlyTheLocalCache() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(1);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, null);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 8L, transaction)).isEqualTo(8L);
            transaction.commit();
        }

        assertThat(storage.resource(0)).isEqualTo(iron);
        assertThat(storage.amount(0)).isEqualTo(8L);
    }

    @Test
    void fullLocalCacheRejectsOutputWhenTheNetworkIsDisconnected() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(1);
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 64L));
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, null);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 1L, transaction)).isZero();
            transaction.commit();
        }

        assertThat(storage.amount(0)).isEqualTo(64L);
    }

    @Test
    void rollbackDoesNotTouchEitherDestination() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 3L);
        GenericStackInv cache = inventory(1);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, network);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 8L, transaction)).isEqualTo(8L);
        }

        assertThat(network.amount(AEItemKey.of(iron))).isZero();
        assertThat(storage.amount(0)).isZero();
        assertThat(network.calls()).extracting(FakeMEStorage.InsertCall::mode)
                .containsOnly(Actionable.SIMULATE);
    }

    @Test
    void itemAndFluidViewsShareTheSameLocalReservationIdentity() {
        GenericStackInv cache = inventory(1);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);

        assertThat(itemStorage(cache, network).reservationIdentity())
                .isSameAs(new OutputResourceStorage<>(cache, () -> network,
                        FluidResourceStorage.adapter(), IActionSource.empty(), () -> {
                        }).reservationIdentity());
    }

    @Test
    void outputPlanningReservesNetworkBeforeLocalCapacity() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 3L);
        GenericStackInv cache = inventory(1);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, network);
        PlanningReservations reservations = new PlanningReservations();

        var plan = storage.planOutput(iron, 8L, reservations, true);

        assertThat(plan.accepted()).isEqualTo(8L);
        assertThat(plan.operation()).isNotNull();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(plan.operation().commit(transaction).success()).isTrue();
            assertThat(network.amount(AEItemKey.of(iron))).isZero();
            assertThat(storage.amount(0)).isEqualTo(5L);
            transaction.commit();
        }
        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(3L);
    }

    @Test
    void outputPlanningRespectsNetworkReservationsAcrossPlans() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 3L);
        OutputResourceStorage<ItemResource> storage = itemStorage(inventory(1), network);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(storage.planOutput(iron, 8L, reservations, false).accepted()).isEqualTo(8L);
        assertThat(reservations.outputAvailable(network, AEItemKey.of(iron), 3L)).isZero();
    }

    @Test
    void outputCapacityIncludesNetworkAndCompatibleLocalSlots() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 3L);
        OutputResourceStorage<ItemResource> storage = itemStorage(inventory(1), network);

        assertThat(storage.outputCapacity(iron)).isEqualTo(67L);
    }

    @Test
    void occupiedSlotForAnotherResourceIsNotOverwritten() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        ItemResource gold = ItemResource.of(Items.GOLD_INGOT);
        GenericStackInv cache = inventory(1);
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 4L));
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(gold), 8L);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, network);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, gold, 8L, transaction)).isEqualTo(8L);
            transaction.commit();
        }

        assertThat(network.amount(AEItemKey.of(gold))).isEqualTo(8L);
        assertThat(storage.resource(0)).isEqualTo(iron);
        assertThat(storage.amount(0)).isEqualTo(4L);
    }

    @Test
    void outputIgnoresAStorageFilterThatWouldBeUsedForInterfaceConfiguration() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = new RejectedInventory();
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, null);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 1L, transaction)).isEqualTo(1L);
            transaction.commit();
        }

        assertThat(storage.resource(0)).isEqualTo(iron);
    }

    @Test
    void networkAcceptanceIsAttemptedBeforeTheLocalFilter() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 8L);
        OutputResourceStorage<ItemResource> storage = itemStorage(new RejectedInventory(), network);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 8L, transaction)).isEqualTo(8L);
            transaction.commit();
        }

        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(8L);
    }

    @Test
    void networkAcceptanceIsAttemptedForAKeyTypeNotSupportedLocally() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = new GenericStackInv(Set.of(AEKeyType.fluids()), null,
                GenericStackInv.Mode.STORAGE, 1);
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 8L);
        OutputResourceStorage<ItemResource> storage = itemStorage(cache, network);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 8L, transaction)).isEqualTo(8L);
            transaction.commit();
        }

        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(8L);
        assertThat(cache.getStack(0)).isNull();
    }

    @Test
    void localCommitInvokesTheChangeCallbackOnce() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        AtomicInteger changes = new AtomicInteger();
        OutputResourceStorage<ItemResource> storage = new OutputResourceStorage<>(inventory(1),
                () -> null, ItemResourceStorage.adapter(), IActionSource.empty(), changes::incrementAndGet);

        try (Transaction transaction = Transaction.openRoot()) {
            storage.insert(0, iron, 1L, transaction);
            assertThat(changes).hasValue(0);
            transaction.commit();
        }

        assertThat(changes).hasValue(1);
    }

    @Test
    void partialFlushRemovesOnlyWhatTheNetworkAccepted() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(1);
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 8L));
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        network.setModulationLimit(3L);
        AtomicInteger changes = new AtomicInteger();
        OutputResourceStorage<ItemResource> storage = new OutputResourceStorage<>(cache, () -> network,
                ItemResourceStorage.adapter(), source(), changes::incrementAndGet);

        assertThat(storage.flushToNetwork(8L)).isEqualTo(3L);
        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(3L);
        assertThat(storage.amount(0)).isEqualTo(5L);
        assertThat(changes).hasValue(1);
    }

    @Test
    void failedFlushLeavesTheLocalCacheUnchanged() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(1);
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 8L));
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        network.setModulationLimit(0L);
        AtomicInteger changes = new AtomicInteger();
        OutputResourceStorage<ItemResource> storage = new OutputResourceStorage<>(cache, () -> network,
                ItemResourceStorage.adapter(), IActionSource.empty(), changes::incrementAndGet);

        assertThat(storage.flushToNetwork(8L)).isZero();
        assertThat(network.amount(AEItemKey.of(iron))).isZero();
        assertThat(storage.amount(0)).isEqualTo(8L);
        assertThat(changes).hasValue(0);
    }

    @Test
    void flushDoesNotExceedItsOperationLimit() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(1);
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 8L));
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        OutputResourceStorage<ItemResource> storage = new OutputResourceStorage<>(cache, () -> network,
                ItemResourceStorage.adapter(), source(), () -> {
                });

        assertThat(storage.flushToNetwork(3L)).isEqualTo(3L);

        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(3L);
        assertThat(storage.amount(0)).isEqualTo(5L);
        assertThat(network.calls()).allSatisfy(call -> assertThat(call.amount()).isLessThanOrEqualTo(3L));
    }

    @Test
    void flushAppliesTheBoundedPolicyEvenForAnUnboundedRequest() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        GenericStackInv cache = inventory(9);
        for (int slot = 0; slot < cache.size(); slot++) {
            cache.setStack(slot, new GenericStack(AEItemKey.of(iron), 64L));
        }
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 1_024L);
        OutputResourceStorage<ItemResource> storage = new OutputResourceStorage<>(cache, () -> network,
                ItemResourceStorage.adapter(), source(), () -> {
                });

        assertThat(storage.flushToNetwork(Long.MAX_VALUE)).isEqualTo(256L);
        assertThat(network.amount(AEItemKey.of(iron))).isEqualTo(256L);
    }

    @Test
    void failedLocalExtractionDoesNotModulateTheNetwork() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FlakyExtractInventory cache = new FlakyExtractInventory();
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 8L));
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        OutputResourceStorage<ItemResource> storage = new OutputResourceStorage<>(cache, () -> network,
                ItemResourceStorage.adapter(), source(), () -> {
                });

        assertThat(storage.flushToNetwork(8L)).isZero();

        assertThat(network.amount(AEItemKey.of(iron))).isZero();
        assertThat(storage.amount(0)).isEqualTo(8L);
        assertThat(network.calls()).extracting(FakeMEStorage.InsertCall::mode)
                .doesNotContain(Actionable.MODULATE);
    }

    @Test
    void failedFlushRestorationRollsBackTheCacheAndNetworkInsertion() {
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        RestoreFailureInventory cache = new RestoreFailureInventory();
        cache.setStack(0, new GenericStack(AEItemKey.of(iron), 8L));
        cache.failRestore = true;
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(iron), 64L);
        network.setModulationLimit(3L);
        OutputResourceStorage<ItemResource> storage = new OutputResourceStorage<>(cache, () -> network,
                ItemResourceStorage.adapter(), source(), () -> {
                });

        assertThatThrownBy(() -> storage.flushToNetwork(8L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to restore AE2 output cache");

        assertThat(network.amount(AEItemKey.of(iron))).isZero();
        assertThat(storage.amount(0)).isEqualTo(8L);
    }

    private static OutputResourceStorage<ItemResource> itemStorage(GenericStackInv cache,
                                                                   FakeMEStorage network) {
        return new OutputResourceStorage<>(cache, () -> network,
                ItemResourceStorage.adapter(), source(), () -> {
                });
    }

    private static GenericStackInv inventory(int size) {
        return new GenericStackInv(Set.of(AEKeyType.items(), AEKeyType.fluids()), null,
                GenericStackInv.Mode.STORAGE, size);
    }

    private static IActionSource source() {
        IEnergyService energy = (IEnergyService) Proxy.newProxyInstance(
                IEnergyService.class.getClassLoader(), new Class<?>[]{IEnergyService.class},
                (proxy, method, args) -> method.getName().equals("extractAEPower") ? 1_000_000D
                        : defaultValue(method.getReturnType()));
        IGrid[] grid = new IGrid[1];
        IGridNode node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(), new Class<?>[]{IGridNode.class},
                (proxy, method, args) -> method.getName().equals("getGrid") ? grid[0]
                        : defaultValue(method.getReturnType()));
        grid[0] = (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(), new Class<?>[]{IGrid.class},
                (proxy, method, args) -> method.getName().equals("getEnergyService") ? energy
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

    private static final class RejectedInventory extends GenericStackInv {
        private RejectedInventory() {
            super(Set.of(AEKeyType.items(), AEKeyType.fluids()), null, Mode.STORAGE, 1);
            setFilter((slot, key) -> false);
        }
    }

    private static final class FlakyExtractInventory extends GenericStackInv {
        private int canExtractCalls;

        private FlakyExtractInventory() {
            super(Set.of(AEKeyType.items(), AEKeyType.fluids()), null, Mode.STORAGE, 1);
        }

        @Override
        public boolean canExtract() {
            return canExtractCalls++ == 0;
        }
    }

    private static final class RestoreFailureInventory extends GenericStackInv {
        private boolean failRestore;

        private RestoreFailureInventory() {
            super(Set.of(AEKeyType.items(), AEKeyType.fluids()), null, Mode.STORAGE, 1);
        }

        @Override
        public void setStack(int slot, GenericStack stack) {
            if (failRestore && stack != null && stack.amount() == 5L) {
                throw new IllegalStateException("Unable to restore AE2 output cache");
            }
            super.setStack(slot, stack);
        }
    }

    private static final class FakeMEStorage implements MEStorage {
        private final Map<AEKey, Long> amounts = new HashMap<>();
        private final Map<AEKey, Long> capacities = new HashMap<>();
        private final List<InsertCall> calls = new ArrayList<>();
        private long modulationLimit = Long.MAX_VALUE;
        private long extractedAmount;

        private record InsertCall(AEKey key, long amount, Actionable mode, IActionSource source) {
        }

        private FakeMEStorage(AEKey key, long capacity) {
            capacities.put(key, capacity);
        }

        private void setModulationLimit(long limit) {
            modulationLimit = limit;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            calls.add(new InsertCall(key, amount, mode, source));
            long current = amounts.getOrDefault(key, 0L);
            long capacity = capacities.getOrDefault(key, 0L);
            long available = Math.max(0L, capacity - current);
            if (mode == Actionable.MODULATE) available = Math.min(available, modulationLimit);
            long inserted = Math.min(amount, available);
            if (mode == Actionable.MODULATE && inserted > 0L) amounts.put(key, current + inserted);
            return inserted;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            long current = amounts.getOrDefault(key, 0L);
            long extracted = Math.min(amount, current);
            if (mode == Actionable.MODULATE && extracted > 0L) {
                amounts.put(key, current - extracted);
                extractedAmount += extracted;
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

        private List<InsertCall> calls() {
            return List.copyOf(calls);
        }

        private long extractedAmount() {
            return extractedAmount;
        }
    }
}
