package cn.howxu.mmcr.compat.appliedflux;

import cn.howxu.mmcr.internal.port.IOPortKind;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Isolates optional AppFlux integration from the common runtime.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface AppliedFluxBridge {
    static AppliedFluxBridge get() {
        return AppliedFluxBridgeBootstrap.bridge();
    }

    boolean available();

    List<IOPortKind> portKinds();

    boolean isPort(String id);

    default void registerCapabilities(RegisterCapabilitiesEvent event) {
    }

    @Nullable
    default Identifier portOverlayTexture(IOPortKind kind) {
        return null;
    }
}
