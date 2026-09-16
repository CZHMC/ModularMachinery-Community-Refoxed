package cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded;

import appeng.api.AECapabilities;
import appeng.helpers.InterfaceLogicHost;
import appeng.me.helpers.IGridConnectedBlockEntity;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.ExtendedAEContributor;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded.kind.ExtendedInputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded.kind.ExtendedOutputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded.kind.ExtendedPatternInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded.kind.ExtendedStockingInputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded.kind.OversizeInputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded.kind.OversizeOutputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded.kind.OversizeStockingInputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade.InterfaceJadeDataProvider;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.registry.ModBlockEntities;
import com.glodblock.github.extendedae.container.ContainerExInterface;
import com.glodblock.github.extendedae.container.ContainerExPatternProvider;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;
import snownee.jade.api.IWailaCommonRegistration;

/** Provides ExtendedAE-specific AE2 port kinds after both optional mods are loaded.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class LoadedExtendedAEContributor implements ExtendedAEContributor {
    private static final List<IOPortKind> KINDS = List.of(
            ExtendedInputInterfaceKind.INSTANCE, ExtendedStockingInputInterfaceKind.INSTANCE,
            ExtendedOutputInterfaceKind.INSTANCE, OversizeInputInterfaceKind.INSTANCE,
            OversizeStockingInputInterfaceKind.INSTANCE, OversizeOutputInterfaceKind.INSTANCE,
            ExtendedPatternInterfaceKind.INSTANCE);

    @Override public boolean available() { return true; }
    @Override public List<IOPortKind> portKinds() { return KINDS; }
    @Override public boolean isPort(String id) { return KINDS.stream().anyMatch(kind -> kind.id().equals(id)); }

    @Override
    public boolean openMenu(ServerPlayer player, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof cn.howxu.mmcr.internal.tile.IOPortBlockEntity host)
                || !KINDS.contains(host.kind())) return false;
        if (host.kind() == ExtendedPatternInterfaceKind.INSTANCE) {
            return MenuOpener.open(ContainerExPatternProvider.TYPE, player, MenuLocators.forBlockEntity(host));
        }
        if (isOversize(host.kind())) {
            return MenuOpener.open(ContainerExInterface.TYPE_OVERSIZE, player, MenuLocators.forBlockEntity(host));
        }
        return MenuOpener.open(ContainerExInterface.TYPE, player, MenuLocators.forBlockEntity(host));
    }

    @Override
    public @Nullable Identifier portOverlayTexture(IOPortKind kind) {
        if (kind == null) return null;
        return switch (kind.id()) {
            case "eae_me_extended_input_interface" -> MMCR.id("block/appliedenergistics2/eae_me_extended_input_interface");
            case "eae_me_extended_stocking_input_interface" -> MMCR.id("block/appliedenergistics2/eae_me_extended_stocking_input_interface");
            case "eae_me_extended_output_interface" -> MMCR.id("block/appliedenergistics2/eae_me_extended_output_interface");
            case "eae_me_oversize_input_interface" -> MMCR.id("block/appliedenergistics2/eae_me_oversize_input_interface");
            case "eae_me_oversize_stocking_input_interface" -> MMCR.id("block/appliedenergistics2/eae_me_oversize_stocking_input_interface");
            case "eae_me_oversize_output_interface" -> MMCR.id("block/appliedenergistics2/eae_me_oversize_output_interface");
            case "eae_me_extended_pattern_interface" -> MMCR.id("block/appliedenergistics2/eae_me_extended_pattern_interface");
            default -> null;
        };
    }

    @Override
    public void registerCapabilities(RegisterCapabilitiesEvent event) {
        registerInterface(event, ExtendedInputInterfaceKind.INSTANCE.id(), InputInterfaceBlockEntity.class, true);
        registerInterface(event, OversizeInputInterfaceKind.INSTANCE.id(), InputInterfaceBlockEntity.class, true);
        registerInterface(event, ExtendedStockingInputInterfaceKind.INSTANCE.id(), StockingInterfaceBlockEntity.class, false);
        registerInterface(event, OversizeStockingInputInterfaceKind.INSTANCE.id(), StockingInterfaceBlockEntity.class, false);
        registerInterface(event, ExtendedOutputInterfaceKind.INSTANCE.id(), OutputInterfaceBlockEntity.class, false);
        registerInterface(event, OversizeOutputInterfaceKind.INSTANCE.id(), OutputInterfaceBlockEntity.class, false);
        BlockEntityType<?> type = type(ExtendedPatternInterfaceKind.INSTANCE.id());
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, type,
                (be, _) -> be instanceof PatternInterfaceBlockEntity host ? host : null);
        event.registerBlockEntity(AECapabilities.GENERIC_INTERNAL_INV, type,
                (be, _) -> be instanceof PatternInterfaceBlockEntity host ? host.getLogic().getReturnInv() : null);
    }

    @Override
    public void registerJadeCommon(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, InputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, StockingInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, OutputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE, PatternInterfaceBlockEntity.class);
    }

    private static boolean isOversize(IOPortKind kind) {
        return kind == OversizeInputInterfaceKind.INSTANCE || kind == OversizeStockingInputInterfaceKind.INSTANCE
                || kind == OversizeOutputInterfaceKind.INSTANCE;
    }

    private static BlockEntityType<?> type(String id) { return ModBlockEntities.BES.get(id).get(); }

    private static <T extends cn.howxu.mmcr.internal.tile.IOPortBlockEntity & InterfaceLogicHost
            & IGridConnectedBlockEntity> void registerInterface(
            RegisterCapabilitiesEvent event, String id, Class<T> hostType, boolean storage) {
        BlockEntityType<?> type = type(id);
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, type,
                (be, _) -> hostType.isInstance(be) ? hostType.cast(be) : null);
        if (storage) {
            event.registerBlockEntity(AECapabilities.GENERIC_INTERNAL_INV, type,
                    (be, _) -> hostType.isInstance(be) ? hostType.cast(be).getInterfaceLogic().getStorage() : null);
            event.registerBlockEntity(AECapabilities.ME_STORAGE, type,
                    (be, _) -> hostType.isInstance(be) ? hostType.cast(be).getInterfaceLogic().getInventory() : null);
        }
    }
}
