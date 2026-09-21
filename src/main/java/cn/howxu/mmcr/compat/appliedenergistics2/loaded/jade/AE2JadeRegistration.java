package cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.AsyncOutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import cn.howxu.mmcr.internal.block.IOPortBlock;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;

/**
 * Registers Jade providers only after both Jade and AE2 are available.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2JadeRegistration {
    private AE2JadeRegistration() {
    }

    public static void registerCommon(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, InputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, StockingInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, OutputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, AsyncOutputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, PatternInterfaceBlockEntity.class);
    }

    public static void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(InterfaceJadeComponentProvider.INSTANCE, IOPortBlock.class);
    }
}
