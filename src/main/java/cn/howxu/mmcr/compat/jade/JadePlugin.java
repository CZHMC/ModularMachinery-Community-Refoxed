package cn.howxu.mmcr.compat.jade;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2InputInterfaceBlockEntity;
import cn.howxu.mmcr.internal.block.IOPortBlock;
import cn.howxu.mmcr.internal.block.MachineControllerBlock;
import cn.howxu.mmcr.internal.block.ParallelControllerBlock;
import cn.howxu.mmcr.internal.runtime.JadeTextSupport;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * @author howxu <dev@howxu.cn>
 */
@WailaPlugin
public final class JadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        JadeTextSupport.enable();
        registration.registerBlockDataProvider(MachineControllerDataProvider.INSTANCE, MachineControllerBlockEntity.class);
        registration.registerBlockDataProvider(RecipeOutputDataProvider.INSTANCE, MachineControllerBlockEntity.class);
        registration.registerBlockDataProvider(AE2InputInterfaceDataProvider.INSTANCE, AE2InputInterfaceBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.addConfig(MachineControllerComponentProvider.UID, true);
        registration.registerBlockComponent(MachineControllerComponentProvider.INSTANCE, MachineControllerBlock.class);
        registration.addConfig(RecipeOutputComponentProvider.UID, true);
        registration.registerBlockComponent(RecipeOutputComponentProvider.INSTANCE, MachineControllerBlock.class);
        registration.addConfig(ParallelControllerComponentProvider.UID, true);
        registration.registerBlockComponent(ParallelControllerComponentProvider.INSTANCE, ParallelControllerBlock.class);
        try {
            Class<?> blockClass = Class.forName("cn.howxu.mmcr.registry.ModBlocks");
            Object blockHolder = blockClass.getMethod("BLOCKS").invoke(null);
            Object portHolder = ((java.util.Map<?, ?>) blockHolder).get("ae2_me_input_interface");
            if (portHolder != null) {
                Object block = portHolder.getClass().getMethod("get").invoke(portHolder);
                if (block instanceof IOPortBlock) {
                    registration.addConfig(AE2InputInterfaceComponentProvider.UID, true);
                    registration.registerBlockComponent(AE2InputInterfaceComponentProvider.INSTANCE, (Class<? extends net.minecraft.world.level.block.Block>) block.getClass());
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
