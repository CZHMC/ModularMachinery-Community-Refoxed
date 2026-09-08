package cn.howxu.mmcr.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author howxu <dev@howxu.cn>
 */
class SaturatingLongTest {

    @Test
    void addClampsPositiveOverflowAndIgnoresNegativeAmounts() {
        assertThat(SaturatingLong.add(4L, 6L)).isEqualTo(10L);
        assertThat(SaturatingLong.add(Long.MAX_VALUE, 1L)).isEqualTo(Long.MAX_VALUE);
        assertThat(SaturatingLong.add(4L, -1L)).isEqualTo(4L);
        assertThat(SaturatingLong.add(-1L, 4L)).isEqualTo(4L);
    }

    @Test
    void multiplyClampsPositiveOverflowAndRejectsNonPositiveFactors() {
        assertThat(SaturatingLong.multiply(4L, 6L)).isEqualTo(24L);
        assertThat(SaturatingLong.multiply(Long.MAX_VALUE, 2L)).isEqualTo(Long.MAX_VALUE);
        assertThat(SaturatingLong.multiply(4L, 0L)).isZero();
        assertThat(SaturatingLong.multiply(-1L, 4L)).isZero();
    }
}
