package cn.howxu.mmcr.compat.appliedflux;

import cn.howxu.mmcr.internal.port.IOPortKind;
import java.util.List;

/**
 * Inert bridge used when AppFlux is unavailable.
 *
 * @author howxu <dev@howxu.cn>
 */
final class UnavailableAppliedFluxBridge implements AppliedFluxBridge {
    static final UnavailableAppliedFluxBridge INSTANCE = new UnavailableAppliedFluxBridge();

    private UnavailableAppliedFluxBridge() {
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<IOPortKind> portKinds() {
        return List.of();
    }

    @Override
    public boolean isPort(String id) {
        return false;
    }
}
