package cn.howxu.mmcr.compat.mekanism.loaded;

import mekanism.common.config.MekanismConfig;
import mekanism.common.util.UnitDisplayUtils.TemperatureUnit;

/** Provides Mekanism-configured temperature conversion for presentation values.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismTemperatureDisplay {
    private MekanismTemperatureDisplay() {
    }

    public static TemperatureUnit configuredUnit() {
        try {
            return MekanismConfig.common.tempUnit.get();
        } catch (IllegalStateException | LinkageError ignored) {
            return TemperatureUnit.KELVIN;
        }
    }

    public static double fromKelvin(double kelvin) {
        return fromKelvin(kelvin, configuredUnit());
    }

    public static double fromKelvin(double kelvin, TemperatureUnit unit) {
        return unit.convertFromK(kelvin, true);
    }

    public static String symbol(TemperatureUnit unit) {
        return unit.getSymbol(false);
    }
}
