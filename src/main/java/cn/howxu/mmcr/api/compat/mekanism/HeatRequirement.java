package cn.howxu.mmcr.api.compat.mekanism;

import java.util.Objects;

/**
 * Mekanism-neutral heat requirement or output declaration.
 *
 * @param kind whether the value is a minimum temperature or output heat
 * @param value the temperature or heat value
 * @author howxu <dev@howxu.cn>
 */
public record HeatRequirement(Kind kind, double value) {
    public HeatRequirement {
        Objects.requireNonNull(kind, "kind");
        if (!Double.isFinite(value) || value < 0D) throw new IllegalArgumentException("value must be non-negative");
    }

    public enum Kind {
        MINIMUM_TEMPERATURE,
        OUTPUT_HEAT
    }

    public static HeatRequirement minimumTemperature(double temperature) {
        return new HeatRequirement(Kind.MINIMUM_TEMPERATURE, temperature);
    }

    public static HeatRequirement outputHeat(double heat) {
        return new HeatRequirement(Kind.OUTPUT_HEAT, heat);
    }
}
