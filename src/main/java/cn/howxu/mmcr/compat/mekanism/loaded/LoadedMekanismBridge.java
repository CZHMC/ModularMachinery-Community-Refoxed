package cn.howxu.mmcr.compat.mekanism.loaded;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.compat.mekanism.MekanismBridge;
import net.minecraft.resources.Identifier;

/**
 * Mekanism-present bridge implementation reserved for Mekanism-specific handlers.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class LoadedMekanismBridge implements MekanismBridge {
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean supportsPortFamily(Identifier familyId) {
        return false;
    }

    @Override
    public Identifier unavailableReason() {
        return MMCR.id("mekanism_available");
    }

    @Override
    public void registerRecipeTypes(Identifier chemical, Identifier heatTemperature, Identifier heat) {
    }
}
