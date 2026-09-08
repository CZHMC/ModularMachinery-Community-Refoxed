package cn.howxu.mmcr.compat.mekanism;

import net.minecraft.resources.Identifier;

/**
 * Isolates optional Mekanism integration from the common runtime.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface MekanismBridge {
    static MekanismBridge get() {
        return MekanismBridgeBootstrap.bridge();
    }

    boolean available();

    boolean supportsPortFamily(Identifier familyId);

    Identifier unavailableReason();

    void registerRecipeTypes(Identifier chemical, Identifier heatTemperature, Identifier heat);
}
