package cn.howxu.mmcr.compat.mekanism;

import cn.howxu.mmcr.api.compat.mekanism.MekanismFailureReasons;
import net.minecraft.resources.Identifier;

/**
 * Inert bridge used when Mekanism is unavailable.
 *
 * @author howxu <dev@howxu.cn>
 */
final class UnavailableMekanismBridge implements MekanismBridge {
    static final UnavailableMekanismBridge INSTANCE = new UnavailableMekanismBridge();

    private UnavailableMekanismBridge() {
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean supportsPortFamily(Identifier familyId) {
        return false;
    }

    @Override
    public Identifier unavailableReason() {
        return MekanismFailureReasons.MEKANISM_UNAVAILABLE.id();
    }

    @Override
    public void registerRecipeTypes(Identifier chemical, Identifier heatTemperature, Identifier heat) {
    }
}
