package cn.howxu.mmcr.client;

import net.minecraft.world.InteractionHand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the blueprint screen opening input predicate.
 * @author howxu <dev@howxu.cn>
 */
class BlueprintClientHandlerTest {
    @Test
    void handlesOnlyMainHandAirUseWithoutShiftOrScreen() {
        assertThat(BlueprintClientHandler.shouldHandle(true, InteractionHand.MAIN_HAND,
                false, false, true, true)).isTrue();
    }

    @Test
    void rejectsOffhandShiftScreenBlockAndUnheldInputs() {
        assertThat(BlueprintClientHandler.shouldHandle(true, InteractionHand.OFF_HAND,
                false, false, true, true)).isFalse();
        assertThat(BlueprintClientHandler.shouldHandle(true, InteractionHand.MAIN_HAND,
                true, false, true, true)).isFalse();
        assertThat(BlueprintClientHandler.shouldHandle(true, InteractionHand.MAIN_HAND,
                false, true, true, true)).isFalse();
        assertThat(BlueprintClientHandler.shouldHandle(true, InteractionHand.MAIN_HAND,
                false, false, false, true)).isFalse();
        assertThat(BlueprintClientHandler.shouldHandle(true, InteractionHand.MAIN_HAND,
                false, false, true, false)).isFalse();
    }
}
