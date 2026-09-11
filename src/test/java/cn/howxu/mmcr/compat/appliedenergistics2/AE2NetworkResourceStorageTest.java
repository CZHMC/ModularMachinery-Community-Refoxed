package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2ItemNetworkResourceStorage;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        AE2ItemNetworkResourceStorage storage = new AE2ItemNetworkResourceStorage(
                network, List.of(ironKey));
        storage.onStackChange(ironKey, 9L);

        assertThat(storage.amount(0)).isEqualTo(9L);
        assertThat(storage.resource(0)).isNotNull();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, storage.resource(0), 1L, transaction)).isZero();
            transaction.commit();
        }

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.extract(0, storage.resource(0), 6L, transaction)).isEqualTo(5L);
            transaction.commit();
        }
        assertThat(network.amount(ironKey)).isZero();
    }

    private static final class FakeMEStorage implements MEStorage {
        private final Map<AEKey, Long> amounts = new HashMap<>();

        private FakeMEStorage(AEKey key, long amount) {
            amounts.put(key, amount);
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            long extracted = Math.min(amount, amounts.getOrDefault(key, 0L));
            if (mode == Actionable.MODULATE) amounts.put(key, amounts.get(key) - extracted);
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
