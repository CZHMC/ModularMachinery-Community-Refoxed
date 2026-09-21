package cn.howxu.mmcr.compat.appliedflux.loaded.jade;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade.InterfaceJadeDataProvider;
import cn.howxu.mmcr.compat.appliedflux.loaded.tile.FluxEnergyInputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedflux.loaded.tile.FluxEnergyOutputInterfaceBlockEntity;
import snownee.jade.api.IWailaCommonRegistration;

/**
 * Registers Jade providers only after both Jade and Applied Flux are available.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AppliedFluxJadeRegistration {
    private AppliedFluxJadeRegistration() {
    }

    public static void registerCommon(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                FluxEnergyInputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                FluxEnergyOutputInterfaceBlockEntity.class);
    }
}
