package cn.howxu.mmcr.compat.mekanism;

import net.minecraft.resources.Identifier;

/**
 * Stable recipe type identities used by the optional Mekanism bridge.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismRecipeTypes {
    public static final Identifier CHEMICAL = Identifier.fromNamespaceAndPath("mekanism", "chemical");
    public static final Identifier HEAT_TEMPERATURE = Identifier.fromNamespaceAndPath("mekanism", "temperature");
    public static final Identifier HEAT = Identifier.fromNamespaceAndPath("mekanism", "heat");

    private MekanismRecipeTypes() {
    }

    public static void register() {
        MekanismBridge.get().registerRecipeTypes(CHEMICAL, HEAT_TEMPERATURE, HEAT);
    }
}
