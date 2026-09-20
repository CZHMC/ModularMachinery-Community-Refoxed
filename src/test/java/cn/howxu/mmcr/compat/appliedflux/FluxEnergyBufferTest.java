package cn.howxu.mmcr.compat.appliedflux;

import cn.howxu.mmcr.compat.appliedflux.loaded.storage.FluxEnergyBuffer;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies persistence and transaction invariants of the local Flux cache.
 *
 * @author howxu <dev@howxu.cn>
 */
class FluxEnergyBufferTest {
    @Test
    void inputReservationSurvivesIdleCacheChecks() {
        FluxEnergyBuffer buffer = new FluxEnergyBuffer();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(buffer.insert(15_000L, transaction)).isEqualTo(15_000L);
            buffer.reserve(5_000L, transaction);
            for (int tick = 0; tick < FluxEnergyBuffer.IDLE_DELAY_TICKS; tick++) buffer.advanceIdle(transaction);
            transaction.commit();
        }

        assertThat(buffer.amount()).isEqualTo(15_000L);
        assertThat(buffer.reserved()).isEqualTo(5_000L);
        assertThat(buffer.idleExcess()).isEqualTo(10_000L);
        assertThat(buffer.isIdleReady()).isFalse();
    }

    @Test
    void inputReleaseMovesUnusedReservationToIdleState() {
        FluxEnergyBuffer buffer = new FluxEnergyBuffer();
        try (Transaction transaction = Transaction.openRoot()) {
            buffer.insert(100L, transaction);
            buffer.reserve(100L, transaction);
            assertThat(buffer.releaseReservation(40L, transaction)).isEqualTo(40L);
            transaction.commit();
        }

        assertThat(buffer.amount()).isEqualTo(100L);
        assertThat(buffer.reserved()).isEqualTo(60L);
        assertThat(buffer.idleExcess()).isEqualTo(40L);
    }

    @Test
    void transactionRollbackRestoresAmountReservationAndIdleMetadata() {
        FluxEnergyBuffer buffer = new FluxEnergyBuffer();
        try (Transaction transaction = Transaction.openRoot()) {
            buffer.insert(100L, transaction);
            buffer.reserve(70L, transaction);
            transaction.commit();
        }

        try (Transaction ignored = Transaction.openRoot()) {
            buffer.extract(30L, ignored);
            buffer.releaseReservation(20L, ignored);
            buffer.insert(50L, ignored);
            buffer.advanceIdle(ignored);
        }

        assertThat(buffer.amount()).isEqualTo(100L);
        assertThat(buffer.reserved()).isEqualTo(70L);
        assertThat(buffer.idleExcess()).isEqualTo(30L);
        assertThat(buffer.idleTicks()).isZero();
    }

    @Test
    void outputPendingEnergySurvivesTransactionRollbackAndSaveLoad() {
        FluxEnergyBuffer pending = new FluxEnergyBuffer();
        try (Transaction ignored = Transaction.openRoot()) {
            assertThat(pending.insert(90L, ignored)).isEqualTo(90L);
        }
        assertThat(pending.amount()).isZero();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(pending.insert(90L, transaction)).isEqualTo(90L);
            transaction.commit();
        }
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                HolderLookup.Provider.create(Stream.empty()));
        pending.save(output);
        FluxEnergyBuffer restored = new FluxEnergyBuffer();
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, HolderLookup.Provider.create(Stream.empty()),
                output.buildResult()));

        assertThat(restored.amount()).isEqualTo(90L);
        assertThat(restored.idleExcess()).isEqualTo(90L);
    }
}
