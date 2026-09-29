package cn.howxu.mmcr.client.model;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerIdleEasterEggTrackerTest {
    private static final BlockPos POS = new BlockPos(4, 8, 15);

    @Test
    void successful_check_plays_one_complete_animation_then_restores_idle() {
        ControllerIdleEasterEggTracker tracker = new ControllerIdleEasterEggTracker(() -> 0.0D);
        tracker.track(POS, 0L);

        assertThat(tracker.tick(1199L, ignored -> true)).isEmpty();
        assertThat(tracker.tick(1200L, ignored -> true)).containsExactly(POS);
        assertThat(tracker.isActive(POS, 1200L)).isTrue();
        assertThat(tracker.tick(1303L, ignored -> true)).isEmpty();
        assertThat(tracker.tick(1304L, ignored -> true)).containsExactly(POS);
        assertThat(tracker.isActive(POS, 1304L)).isFalse();
    }

    @Test
    void untracked_controller_cannot_keep_an_active_easter_egg() {
        ControllerIdleEasterEggTracker tracker = new ControllerIdleEasterEggTracker(() -> 0.0D);
        tracker.track(POS, 0L);
        tracker.tick(1200L, ignored -> true);

        tracker.untrack(POS);

        assertThat(tracker.isActive(POS, 1200L)).isFalse();
    }

    @Test
    void delayed_tick_reports_one_change_when_expiry_and_next_trigger_are_both_overdue() {
        ControllerIdleEasterEggTracker tracker = new ControllerIdleEasterEggTracker(() -> 0.0D);
        tracker.track(POS, 0L);
        tracker.tick(1200L, ignored -> true);

        assertThat(tracker.tick(2500L, ignored -> true)).containsExactly(POS);
        assertThat(tracker.isActive(POS, 2500L)).isTrue();
    }
}
