package cn.howxu.mmcr.api.compat.mekanism;

import cn.howxu.mmcr.MMCR;
import net.minecraft.resources.Identifier;

/**
 * Stable recipe and port family identifiers used by the optional Mekanism bridge.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismPortFamilies {
    public static final Identifier CHEMICAL = MMCR.id("mekanism_chemical");
    public static final Identifier HEAT_TEMPERATURE = MMCR.id("mekanism_heat_temperature");
    public static final Identifier HEAT = MMCR.id("mekanism_heat");

    private MekanismPortFamilies() {
    }
}
