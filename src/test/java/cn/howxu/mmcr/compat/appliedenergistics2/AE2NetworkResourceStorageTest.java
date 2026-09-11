package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2FluidNetworkResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2ItemNetworkResourceStorage;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the event-backed AE2 network resource view extracts from live ME storage.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2NetworkResourceStorageTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void watcherAmountIsDisplayOnlyAndExtractionUsesLiveStorage() {
        AEKey ironKey = AEItemKey.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(ironKey, 5L);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        AE2ItemNetworkResourceStorage storage = new AE2ItemNetworkResourceStorage(
                network, List.of(ironKey));
        storage.onStackChange(ironKey, 9L);

        assertThat(storage.amount(0)).isEqualTo(9L);
        assertThat(storage.resource(0)).isEqualTo(iron);
        assertThat(storage.reservationIdentity()).isSameAs(network);
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, storage.resource(0), 1L, transaction)).isZero();
            transaction.commit();
        }
        assertThat(network.insertCalls()).isZero();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.extract(0, storage.resource(0), 6L, transaction)).isEqualTo(5L);
            transaction.commit();
        }
        assertThat(network.amount(ironKey)).isZero();
        assertThat(storage.amount(0)).isEqualTo(9L);
        storage.onStackChange(ironKey, -1L);
        assertThat(storage.amount(0)).isZero();
    }

    @Test
    void fluidViewUsesFluidAdapterAndLiveExtraction() {
        AEKey waterKey = AEFluidKey.of(Fluids.WATER);
        FluidResource water = FluidResource.of(Fluids.WATER);
        FakeMEStorage network = new FakeMEStorage(waterKey, 8L);
        AE2FluidNetworkResourceStorage storage = new AE2FluidNetworkResourceStorage(
                network, List.of(waterKey));

        assertThat(storage.resource(0)).isEqualTo(water);
        assertThat(storage.resourceType()).isEqualTo(FluidResource.class);
        assertThat(storage.capacity(0, water)).isEqualTo(Long.MAX_VALUE);
        assertThat(storage.reservationIdentity()).isSameAs(network);
        storage.onStackChange(waterKey, 12L);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.extract(0, water, 20L, transaction)).isEqualTo(8L);
            transaction.commit();
        }

        assertThat(network.amount(waterKey)).isZero();
        assertThat(storage.amount(0)).isEqualTo(12L);
    }

    @Test
    void configuredKeysStayInFixedSlots() {
        AEKey ironKey = AEItemKey.of(Items.IRON_INGOT);
        AEKey goldKey = AEItemKey.of(Items.GOLD_INGOT);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        ItemResource gold = ItemResource.of(Items.GOLD_INGOT);
        FakeMEStorage network = new FakeMEStorage(ironKey, 5L);
        network.setAmount(goldKey, 3L);
        AE2ItemNetworkResourceStorage storage = new AE2ItemNetworkResourceStorage(
                network, List.of(ironKey, goldKey));

        assertThat(storage.size()).isEqualTo(2);
        assertThat(storage.resource(0)).isEqualTo(iron);
        assertThat(storage.resource(1)).isEqualTo(gold);
        assertThat(storage.capacity(0, null)).isEqualTo(Long.MAX_VALUE);
        assertThat(storage.capacity(1, gold)).isEqualTo(Long.MAX_VALUE);
        assertThat(storage.isValid(0, iron)).isTrue();
        assertThat(storage.isValid(0, gold)).isFalse();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, gold, 1L, transaction)).isZero();
            assertThat(storage.extract(0, gold, 1L, transaction)).isZero();
            transaction.commit();
        }
        assertThat(network.insertCalls()).isZero();
        assertThat(network.extractCalls()).isZero();
    }

    @Test
    void rejectsInvalidSlotsAndResources() {
        AEKey ironKey = AEItemKey.of(Items.IRON_INGOT);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FluidResource water = FluidResource.of(Fluids.WATER);
        AE2ItemNetworkResourceStorage storage = new AE2ItemNetworkResourceStorage(
                new FakeMEStorage(ironKey, 1L), List.of(ironKey));

        assertThatThrownBy(() -> storage.resource(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> storage.amount(1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> storage.capacity(1, null)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> storage.isValid(1, iron)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> storage.insert(-1, iron, 1L, null))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> storage.extract(1, iron, 1L, null))
                .isInstanceOf(IndexOutOfBoundsException.class);

        assertThatThrownBy(() -> storage.insert(0, null, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.extract(0, null, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.insert(0, iron, -1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.extract(0, iron, -1L, null))
                .isInstanceOf(IllegalArgumentException.class);

        @SuppressWarnings("rawtypes")
        ResourceStorage rawStorage = storage;
        assertThatThrownBy(() -> rawStorage.isValid(0, water))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rawStorage.insert(0, water, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rawStorage.extract(0, water, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fakeStorageReturnsZeroForUnknownKeys() {
        AEKey ironKey = AEItemKey.of(Items.IRON_INGOT);
        AEKey goldKey = AEItemKey.of(Items.GOLD_INGOT);
        FakeMEStorage network = new FakeMEStorage(ironKey, 1L);

        assertThat(network.extract(goldKey, 1L, Actionable.MODULATE, IActionSource.empty())).isZero();
        assertThat(network.amount(goldKey)).isZero();
    }

    @Test
    void extractionWaitsForCommitAndAccountsForPendingAmount() {
        AEKey ironKey = AEItemKey.of(Items.IRON_INGOT);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(ironKey, 8L);
        AE2ItemNetworkResourceStorage storage = new AE2ItemNetworkResourceStorage(
                network, List.of(ironKey));

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.extract(0, iron, 5L, transaction)).isEqualTo(5L);
            assertThat(storage.extract(0, iron, 5L, transaction)).isEqualTo(3L);
            assertThat(network.amount(ironKey)).isEqualTo(8L);
            assertThat(network.simulateExtractCalls()).isEqualTo(2);
            assertThat(network.modulateExtractCalls()).isZero();
            transaction.commit();
        }

        assertThat(network.amount(ironKey)).isZero();
        assertThat(network.modulateExtractCalls()).isEqualTo(1);
        assertThat(network.modulatedAmount()).isEqualTo(8L);
    }

    @Test
    void rollbackDiscardsPendingExtractionWithoutTouchingLiveStorage() {
        AEKey ironKey = AEItemKey.of(Items.IRON_INGOT);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        FakeMEStorage network = new FakeMEStorage(ironKey, 8L);
        AE2ItemNetworkResourceStorage storage = new AE2ItemNetworkResourceStorage(
                network, List.of(ironKey));

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.extract(0, iron, 5L, transaction)).isEqualTo(5L);
            assertThat(network.amount(ironKey)).isEqualTo(8L);
        }

        assertThat(network.amount(ironKey)).isEqualTo(8L);
        assertThat(network.modulateExtractCalls()).isZero();
    }

    private static final class FakeMEStorage implements MEStorage {
        private final Map<AEKey, Long> amounts = new HashMap<>();
        private int insertCalls;
        private int extractCalls;
        private int simulateExtractCalls;
        private int modulateExtractCalls;
        private long modulatedAmount;

        private FakeMEStorage(AEKey key, long amount) {
            amounts.put(key, amount);
        }

        private void setAmount(AEKey key, long amount) {
            amounts.put(key, amount);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            insertCalls++;
            return 0L;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            extractCalls++;
            long available = amounts.getOrDefault(key, 0L);
            long extracted = Math.min(amount, available);
            if (mode == Actionable.SIMULATE) {
                simulateExtractCalls++;
            } else if (mode == Actionable.MODULATE && extracted > 0L) {
                modulateExtractCalls++;
                modulatedAmount += extracted;
                amounts.put(key, available - extracted);
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

        private int insertCalls() {
            return insertCalls;
        }

        private int extractCalls() {
            return extractCalls;
        }

        private int simulateExtractCalls() {
            return simulateExtractCalls;
        }

        private int modulateExtractCalls() {
            return modulateExtractCalls;
        }

        private long modulatedAmount() {
            return modulatedAmount;
        }
    }
}
