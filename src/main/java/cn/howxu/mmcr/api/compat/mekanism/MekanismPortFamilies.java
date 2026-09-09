package cn.howxu.mmcr.api.compat.mekanism;

import cn.howxu.mmcr.MMCR;
import net.minecraft.resources.Identifier;

/**
 * Stable recipe and port family identifiers used by the optional Mekanism bridge.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismPortFamilies {
    public static final Identifier CHEMICAL = Identifier.fromNamespaceAndPath("mekanism", "chemical");
    public static final Identifier RADIOACTIVE_CHEMICAL = MMCR.id("mekanism_radioactive_chemical");
    public static final Identifier HEAT_TEMPERATURE = Identifier.fromNamespaceAndPath("mekanism", "temperature");
    public static final Identifier HEAT = Identifier.fromNamespaceAndPath("mekanism", "heat");

    private MekanismPortFamilies() {
    }
}
