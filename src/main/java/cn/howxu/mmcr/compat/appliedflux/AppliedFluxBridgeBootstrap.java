package cn.howxu.mmcr.compat.appliedflux;

import java.util.Objects;
import net.neoforged.fml.ModList;

/**
 * Lazily selects the optional AppFlux bridge without linking its implementation early.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AppliedFluxBridgeBootstrap {
    private static final String APPFLUX_MOD_ID = "appflux";
    private static final String LOADED_BRIDGE =
            "cn.howxu.mmcr.compat.appliedflux.loaded.LoadedAppliedFluxBridge";
    private static volatile AppliedFluxBridge bridge;
    private static volatile AppliedFluxBridge testingBridge;

    private AppliedFluxBridgeBootstrap() {
    }

    static AppliedFluxBridge bridge() {
        AppliedFluxBridge override = testingBridge;
        if (override != null) return override;

        AppliedFluxBridge selected = bridge;
        if (selected != null) return selected;

        synchronized (AppliedFluxBridgeBootstrap.class) {
            if (bridge == null) bridge = load();
            return bridge;
        }
    }

    public static AppliedFluxBridge selectForTesting(boolean appFluxLoaded) {
        return appFluxLoaded ? loadLoadedBridge() : UnavailableAppliedFluxBridge.INSTANCE;
    }

    public static void installForTesting(AppliedFluxBridge testingBridge) {
        AppliedFluxBridgeBootstrap.testingBridge = Objects.requireNonNull(testingBridge);
    }

    public static synchronized void resetForTesting() {
        testingBridge = null;
        bridge = null;
    }

    private static AppliedFluxBridge load() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(APPFLUX_MOD_ID) ? loadLoadedBridge() : UnavailableAppliedFluxBridge.INSTANCE;
    }

    private static AppliedFluxBridge loadLoadedBridge() {
        try {
            return (AppliedFluxBridge) Class.forName(LOADED_BRIDGE).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to load AppFlux integration", exception);
        }
    }
}
