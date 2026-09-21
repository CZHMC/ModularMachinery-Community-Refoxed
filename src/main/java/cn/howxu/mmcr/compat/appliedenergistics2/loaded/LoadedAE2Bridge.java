package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.AECapabilities;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.blockentity.networking.WirelessAccessPointBlockEntity;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.InterfaceMenu;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.menu.locator.MenuLocators;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.compat.appliedenergistics2.AE2Bridge;
import cn.howxu.mmcr.compat.extendedae.ExtendedAEContributor;
import cn.howxu.mmcr.compat.extendedae.ExtendedAEContributorBootstrap;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade.InterfaceJadeComponentProvider;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade.InterfaceJadeDataProvider;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.AsyncOutputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.InputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.OutputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.PatternInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.StockingInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.AsyncOutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import cn.howxu.mmcr.internal.block.IOPortBlock;
import cn.howxu.mmcr.internal.assembly.StructureItemStorage;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * AE2-present bridge implementation: registers the native input and output port kinds
 * and routes the native Interface UI through AE2's menu opener.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class LoadedAE2Bridge implements AE2Bridge {
    private static final String INPUT_INTERFACE_ID = "ae2_me_input_interface";
    private static final String STOCKING_INTERFACE_ID = "ae2_me_stocking_input_interface";
    private static final String OUTPUT_INTERFACE_ID = "ae2_me_output_interface";
    private static final String ASYNC_OUTPUT_INTERFACE_ID = "ae2_me_async_output_interface";
    private static final String PATTERN_INTERFACE_ID = "ae2_me_pattern_interface";
    private static final Identifier INTERFACE_OVERLAY_TEXTURE =
            MMCR.id("block/appliedenergistics2/ae2_input");
    private static final Identifier STOCKING_INTERFACE_OVERLAY_TEXTURE =
            MMCR.id("block/appliedenergistics2/ae2_stocking_input");
    private static final Identifier OUTPUT_INTERFACE_OVERLAY_TEXTURE =
            MMCR.id("block/appliedenergistics2/ae2_output");
    private static final Identifier ASYNC_OUTPUT_INTERFACE_OVERLAY_TEXTURE =
            MMCR.id("block/appliedenergistics2/ae2_async_output");
    private static final Identifier PATTERN_INTERFACE_OVERLAY_TEXTURE =
            MMCR.id("block/appliedenergistics2/ae2_pattern_interface");
    private final ExtendedAEContributor contributor = ExtendedAEContributorBootstrap.contributor();

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<IOPortKind> portKinds() {
        if (!contributor.available()) {
            return List.of(
                    InputInterfaceKind.INSTANCE,
                    StockingInterfaceKind.INSTANCE,
                    OutputInterfaceKind.INSTANCE,
                    AsyncOutputInterfaceKind.INSTANCE,
                    PatternInterfaceKind.INSTANCE);
        }
        return Stream.concat(List.of(
                InputInterfaceKind.INSTANCE,
                StockingInterfaceKind.INSTANCE,
                OutputInterfaceKind.INSTANCE,
                AsyncOutputInterfaceKind.INSTANCE,
                PatternInterfaceKind.INSTANCE).stream(), contributor.portKinds().stream()).toList();
    }

    @Override
    public boolean isPort(String id) {
        return INPUT_INTERFACE_ID.equals(id)
                || STOCKING_INTERFACE_ID.equals(id)
                || OUTPUT_INTERFACE_ID.equals(id)
                || ASYNC_OUTPUT_INTERFACE_ID.equals(id)
                || PATTERN_INTERFACE_ID.equals(id)
                || contributor.available() && contributor.isPort(id);
    }

    @Override
    public boolean openMenu(ServerPlayer player, Level level, BlockPos pos) {
        if (contributor.available() && contributor.openMenu(player, level, pos)) return true;
        if (level.getBlockEntity(pos) instanceof InputInterfaceBlockEntity host) {
            return MenuOpener.open(InterfaceMenu.TYPE, player, MenuLocators.forBlockEntity(host));
        }
        if (level.getBlockEntity(pos) instanceof StockingInterfaceBlockEntity host) {
            return MenuOpener.open(InterfaceMenu.TYPE, player, MenuLocators.forBlockEntity(host));
        }
        if (level.getBlockEntity(pos) instanceof OutputInterfaceBlockEntity host) {
            return MenuOpener.open(InterfaceMenu.TYPE, player, MenuLocators.forBlockEntity(host));
        }
        if (level.getBlockEntity(pos) instanceof AsyncOutputInterfaceBlockEntity host) {
            return MenuOpener.open(InterfaceMenu.TYPE, player, MenuLocators.forBlockEntity(host));
        }
        if (level.getBlockEntity(pos) instanceof PatternInterfaceBlockEntity host) {
            return MenuOpener.open(PatternProviderMenu.TYPE, player, MenuLocators.forBlockEntity(host));
        }
        return false;
    }

    @Override
    public boolean isWirelessAccessPoint(ServerPlayer player, GlobalPos accessPoint) {
        ServerLevel level = player.level().getServer().getLevel(accessPoint.dimension());
        return level != null && level.hasChunkAt(accessPoint.pos())
                && level.getBlockEntity(accessPoint.pos()) instanceof WirelessAccessPointBlockEntity;
    }

    @Override
    public Optional<StructureItemStorage> resolveTerminalNetworkStorage(ServerPlayer player, GlobalPos accessPoint) {
        ServerLevel level = player.level().getServer().getLevel(accessPoint.dimension());
        if (level == null || !level.hasChunkAt(accessPoint.pos())) return Optional.empty();
        if (!(level.getBlockEntity(accessPoint.pos()) instanceof WirelessAccessPointBlockEntity wirelessAccessPoint)
                || !wirelessAccessPoint.isActive()) return Optional.empty();
        IGrid grid = wirelessAccessPoint.getGrid();
        if (grid == null) return Optional.empty();
        return Optional.of(new AE2NetworkStructureItemStorage(grid.getStorageService().getInventory(),
                IActionSource.ofPlayer(player)));
    }

    @Override
    public void onPortNeighborChanged(IOPortBlockEntity port) {
        if (port instanceof PatternInterfaceBlockEntity host) host.getLogic().updateRedstoneState();
    }

    @Override
    public Identifier portOverlayTexture(IOPortKind kind) {
        if (kind instanceof InputInterfaceKind) return INTERFACE_OVERLAY_TEXTURE;
        if (kind instanceof StockingInterfaceKind) return STOCKING_INTERFACE_OVERLAY_TEXTURE;
        if (kind instanceof OutputInterfaceKind) return OUTPUT_INTERFACE_OVERLAY_TEXTURE;
        if (kind instanceof AsyncOutputInterfaceKind) return ASYNC_OUTPUT_INTERFACE_OVERLAY_TEXTURE;
        if (kind instanceof PatternInterfaceKind) return PATTERN_INTERFACE_OVERLAY_TEXTURE;
        return contributor.available() ? contributor.portOverlayTexture(kind) : null;
    }

    @Override
    public void registerCapabilities(RegisterCapabilitiesEvent event) {
        BlockEntityType<?> inputInterfaceType = ModBlockEntities.BES.get(INPUT_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, inputInterfaceType,
                (be, ignored) -> be instanceof InputInterfaceBlockEntity host ? host : null);
        event.registerBlockEntity(AECapabilities.GENERIC_INTERNAL_INV, inputInterfaceType,
                (be, _) -> be instanceof InputInterfaceBlockEntity host
                        ? host.getInterfaceLogic().getStorage() : null);
        event.registerBlockEntity(AECapabilities.ME_STORAGE, inputInterfaceType,
                (be, _) -> be instanceof InputInterfaceBlockEntity host
                        ? host.getInterfaceLogic().getInventory() : null);
        BlockEntityType<?> stockingInterfaceType = ModBlockEntities.BES.get(STOCKING_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, stockingInterfaceType,
                (be, ignored) -> be instanceof StockingInterfaceBlockEntity host ? host : null);
        BlockEntityType<?> outputInterfaceType = ModBlockEntities.BES.get(OUTPUT_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, outputInterfaceType,
                (be, ignored) -> be instanceof OutputInterfaceBlockEntity host ? host : null);
        BlockEntityType<?> asyncOutputInterfaceType = ModBlockEntities.BES.get(ASYNC_OUTPUT_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, asyncOutputInterfaceType,
                (be, ignored) -> be instanceof AsyncOutputInterfaceBlockEntity host ? host : null);
        BlockEntityType<?> patternInterfaceType = ModBlockEntities.BES.get(PATTERN_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, patternInterfaceType,
                (be, ignored) -> be instanceof PatternInterfaceBlockEntity host ? host : null);
        event.registerBlockEntity(AECapabilities.GENERIC_INTERNAL_INV, patternInterfaceType,
                (be, _) -> be instanceof PatternInterfaceBlockEntity host ? host.getLogic().getReturnInv() : null);
        if (contributor.available()) contributor.registerCapabilities(event);
    }

    @Override
    public void registerJadeCommon(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                InputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                StockingInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                OutputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                AsyncOutputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                PatternInterfaceBlockEntity.class);
        if (contributor.available()) contributor.registerJadeCommon(registration);
    }

    @Override
    public void registerJadeClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(InterfaceJadeComponentProvider.INSTANCE, IOPortBlock.class);
    }
}
