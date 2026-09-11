package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.AECapabilities;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.InterfaceMenu;
import appeng.menu.locator.MenuLocators;
import cn.howxu.mmcr.compat.appliedenergistics2.AE2Bridge;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade.AE2InputInterfaceJadeComponentProvider;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade.AE2InputInterfaceJadeDataProvider;
import cn.howxu.mmcr.internal.block.IOPortBlock;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;

import java.util.List;

/**
 * AE2-present bridge implementation: registers the native input port kind and
 * routes the native Interface UI through AE2's menu opener.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class LoadedAE2Bridge implements AE2Bridge {
    private static final String INPUT_INTERFACE_ID = "ae2_me_input_interface";
    private static final String STOCKING_INTERFACE_ID = "ae2_me_stocking_input_interface";
    private static final Identifier INTERFACE_OVERLAY_TEXTURE =
            Identifier.fromNamespaceAndPath("ae2", "block/interface");

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<IOPortKind> portKinds() {
        return List.of(AE2InputInterfaceKind.INSTANCE, AE2StockingInterfaceKind.INSTANCE);
    }

    @Override
    public boolean isPort(String id) {
        return INPUT_INTERFACE_ID.equals(id) || STOCKING_INTERFACE_ID.equals(id);
    }

    @Override
    public boolean openMenu(ServerPlayer player, Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof AE2InputInterfaceBlockEntity host) {
            return MenuOpener.open(InterfaceMenu.TYPE, player, MenuLocators.forBlockEntity(host));
        }
        if (level.getBlockEntity(pos) instanceof AE2StockingInterfaceBlockEntity host) {
            return MenuOpener.open(InterfaceMenu.TYPE, player, MenuLocators.forBlockEntity(host));
        }
        return false;
    }

    @Override
    public Identifier portOverlayTexture(IOPortKind kind) {
        return isPort(kind.id()) ? INTERFACE_OVERLAY_TEXTURE : null;
    }

    @Override
    public void registerCapabilities(RegisterCapabilitiesEvent event) {
        BlockEntityType<?> inputInterfaceType = ModBlockEntities.BES.get(INPUT_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, inputInterfaceType,
                (be, ignored) -> be instanceof AE2InputInterfaceBlockEntity host ? host : null);
        event.registerBlockEntity(AECapabilities.GENERIC_INTERNAL_INV, inputInterfaceType,
                (be, side) -> be instanceof AE2InputInterfaceBlockEntity host
                        ? host.getInterfaceLogic().getStorage() : null);
        event.registerBlockEntity(AECapabilities.ME_STORAGE, inputInterfaceType,
                (be, side) -> be instanceof AE2InputInterfaceBlockEntity host
                        ? host.getInterfaceLogic().getInventory() : null);
        BlockEntityType<?> stockingInterfaceType = ModBlockEntities.BES.get(STOCKING_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, stockingInterfaceType,
                (be, ignored) -> be instanceof AE2StockingInterfaceBlockEntity host ? host : null);
    }

    @Override
    public void registerJadeCommon(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(AE2InputInterfaceJadeDataProvider.INSTANCE,
                AE2InputInterfaceBlockEntity.class);
    }

    @Override
    public void registerJadeClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(AE2InputInterfaceJadeComponentProvider.INSTANCE, IOPortBlock.class);
    }
}
