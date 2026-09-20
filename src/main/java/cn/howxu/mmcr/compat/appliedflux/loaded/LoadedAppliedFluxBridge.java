package cn.howxu.mmcr.compat.appliedflux.loaded;

import appeng.api.AECapabilities;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.compat.appliedflux.AppliedFluxBridge;
import cn.howxu.mmcr.compat.appliedflux.loaded.kind.FluxEnergyInputKind;
import cn.howxu.mmcr.compat.appliedflux.loaded.kind.FluxEnergyOutputKind;
import cn.howxu.mmcr.compat.appliedflux.loaded.tile.FluxEnergyInputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedflux.loaded.tile.FluxEnergyOutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade.InterfaceJadeDataProvider;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.registry.ModBlockEntities;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import snownee.jade.api.IWailaCommonRegistration;

import java.util.List;

/**
 * AppFlux-present bridge for the AE2-hosted Flux energy interfaces.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class LoadedAppliedFluxBridge implements AppliedFluxBridge {
    private static final String INPUT_INTERFACE_ID = "appflux_me_flux_input_interface";
    private static final String OUTPUT_INTERFACE_ID = "appflux_me_flux_output_interface";
    private static final Identifier INPUT_OVERLAY_TEXTURE =
            MMCR.id("block/appliedflux/appflux_input");
    private static final Identifier OUTPUT_OVERLAY_TEXTURE =
            MMCR.id("block/appliedflux/appflux_output");

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<IOPortKind> portKinds() {
        return List.of(FluxEnergyInputKind.INSTANCE, FluxEnergyOutputKind.INSTANCE);
    }

    @Override
    public boolean isPort(String id) {
        return INPUT_INTERFACE_ID.equals(id) || OUTPUT_INTERFACE_ID.equals(id);
    }

    @Override
    public Identifier portOverlayTexture(IOPortKind kind) {
        if (kind instanceof FluxEnergyInputKind) return INPUT_OVERLAY_TEXTURE;
        if (kind instanceof FluxEnergyOutputKind) return OUTPUT_OVERLAY_TEXTURE;
        return null;
    }

    @Override
    public void registerCapabilities(RegisterCapabilitiesEvent event) {
        BlockEntityType<?> inputInterfaceType = ModBlockEntities.BES.get(INPUT_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, inputInterfaceType,
                (be, ignored) -> be instanceof FluxEnergyInputInterfaceBlockEntity host ? host : null);
        BlockEntityType<?> outputInterfaceType = ModBlockEntities.BES.get(OUTPUT_INTERFACE_ID).get();
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST, outputInterfaceType,
                (be, ignored) -> be instanceof FluxEnergyOutputInterfaceBlockEntity host ? host : null);
    }

    @Override
    public void registerJadeCommon(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                FluxEnergyInputInterfaceBlockEntity.class);
        registration.registerBlockDataProvider(InterfaceJadeDataProvider.INSTANCE,
                FluxEnergyOutputInterfaceBlockEntity.class);
    }
}
