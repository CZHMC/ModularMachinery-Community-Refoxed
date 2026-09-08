package cn.howxu.mmcr.compat.mekanism;

import cn.howxu.mmcr.MMCR;
import net.minecraft.resources.Identifier;

/**
 * Stable recipe type identities used by the optional Mekanism bridge.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismRecipeTypes {
    public static final Identifier CHEMICAL = MMCR.id("mekanism_chemical");
    public static final Identifier HEAT_TEMPERATURE = MMCR.id("mekanism_heat_temperature");
    public static final Identifier HEAT = MMCR.id("mekanism_heat");

    private MekanismRecipeTypes() {
    }

    public static void register() {
        MekanismBridge.get().registerRecipeTypes(CHEMICAL, HEAT_TEMPERATURE, HEAT);
    }
}
