package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2AsyncOutputResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2AsyncOutputService;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ItemResourceStorage;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.test.TestBootstrap;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
        if (!ae2KeyTypesAreInitialized()) initializeAE2KeyTypes();
    }

    @Test
    void serviceIsEmptyWhenNothingSubmitted() {
        AE2AsyncOutputService service = new AE2AsyncOutputService();

        assertThat(service.isEmpty()).isTrue();
    }

    @Test
    void submitAndDrainRemovesAcceptedAmounts() {
        AE2AsyncOutputService service = new AE2AsyncOutputService();
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 64L);

        service.submit(AEItemKey.of(Items.IRON_INGOT), 8L);
        boolean drained = service.drainTo(network, source(), 4);

        assertThat(drained).isTrue();
        assertThat(service.isEmpty()).isTrue();
        assertThat(network.amount(AEItemKey.of(Items.IRON_INGOT))).isEqualTo(8L);
    }

    @Test
    void partialDrainRetainsUnacceptedAmountsInTheTransientAccumulator() {
        AE2AsyncOutputService service = new AE2AsyncOutputService();
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
        AE2AsyncOutputService service = new AE2AsyncOutputService();
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
        AE2AsyncOutputService service = new AE2AsyncOutputService();
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);

        service.submit(null, 4L);
        service.submit(AEItemKey.of(Items.IRON_INGOT), 0L);
        service.submit(AEItemKey.of(Items.IRON_INGOT), -1L);

        assertThat(service.isEmpty()).isTrue();
        assertThat(service.drainTo(network, source(), 1)).isFalse();
    }

    @Test
    void clearRemovesAllPendingWork() {
        AE2AsyncOutputService service = new AE2AsyncOutputService();

        service.submit(AEItemKey.of(Items.IRON_INGOT), 4L);
        service.submit(AEItemKey.of(Items.GOLD_INGOT), 2L);
        service.clear();

        assertThat(service.isEmpty()).isTrue();
    }

    @Test
    void resourceStorageReportsZeroSizeAndNoSlots() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);
        AE2AsyncOutputResourceStorage<ItemResource> storage = new AE2AsyncOutputResourceStorage<>(
                () -> network, AE2ItemResourceStorage.adapter(), new AE2AsyncOutputService(), source(),
                () -> {
                });

        assertThat(storage.size()).isZero();
        assertThat(storage.amount(0)).isZero();
        assertThat(storage.resource(0)).isNull();
    }

    @Test
    void resourceStorageOutputCapacityUsesNetworkSimulation() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 12L);
        AE2AsyncOutputResourceStorage<ItemResource> storage = new AE2AsyncOutputResourceStorage<>(
                () -> network, AE2ItemResourceStorage.adapter(), new AE2AsyncOutputService(), source(),
                () -> {
                });

        assertThat(storage.outputCapacity(ItemResource.of(Items.IRON_INGOT))).isEqualTo(12L);
    }

    @Test
    void resourceStoragePlansNetworkOutputWithReservationIdentity() {
        FakeMEStorage network = new FakeMEStorage(AEItemKey.of(Items.IRON_INGOT), 8L);
        AE2AsyncOutputService service = new AE2AsyncOutputService();
        AE2AsyncOutputResourceStorage<ItemResource> storage = new AE2AsyncOutputResourceStorage<>(
                () -> network, AE2ItemResourceStorage.adapter(), service, source(), () -> {
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
        AE2AsyncOutputService service = new AE2AsyncOutputService();
        AE2AsyncOutputResourceStorage<ItemResource> storage = new AE2AsyncOutputResourceStorage<>(
                () -> network, AE2ItemResourceStorage.adapter(), service, source(), () -> {
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
        AE2AsyncOutputService service = new AE2AsyncOutputService();
        AE2AsyncOutputResourceStorage<ItemResource> storage = new AE2AsyncOutputResourceStorage<>(
                () -> network, AE2ItemResourceStorage.adapter(), service, source(), () -> {
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
    }

    @Test
    void resourceStoragePlanReturnsZeroWhenNetworkIsAbsent() {
        AE2AsyncOutputResourceStorage<ItemResource> storage = new AE2AsyncOutputResourceStorage<>(
                () -> null, AE2ItemResourceStorage.adapter(), new AE2AsyncOutputService(), source(),
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
        AE2AsyncOutputResourceStorage<ItemResource> storage = new AE2AsyncOutputResourceStorage<>(
                () -> network, AE2ItemResourceStorage.adapter(), new AE2AsyncOutputService(), source(),
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
        AE2AsyncOutputResourceStorage<ItemResource> storage = new AE2AsyncOutputResourceStorage<>(
                () -> network, AE2ItemResourceStorage.adapter(), new AE2AsyncOutputService(), source(),
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
