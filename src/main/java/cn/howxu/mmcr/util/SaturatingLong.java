package cn.howxu.mmcr.util;

/**
 * Non-negative long arithmetic that clamps positive overflow.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class SaturatingLong {
    private SaturatingLong() {}

    public static long add(long first, long second) {
        if (first <= 0L) return Math.max(0L, second);
        if (second <= 0L) return first;
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    public static long multiply(long amount, long multiplier) {
        if (amount <= 0L || multiplier <= 0L) return 0L;
        return amount > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : amount * multiplier;
    }
}
