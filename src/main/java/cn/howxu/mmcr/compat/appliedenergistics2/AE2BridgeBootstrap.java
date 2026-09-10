package cn.howxu.mmcr.compat.appliedenergistics2;

import java.util.Objects;
import net.neoforged.fml.ModList;

/**
 * Lazily selects the optional AE2 bridge without linking its implementation early.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class AE2BridgeBootstrap {
    private static final String AE2_MOD_ID = "ae2";
    private static final String LOADED_BRIDGE =
            "cn.howxu.mmcr.compat.appliedenergistics2.loaded.LoadedAE2Bridge";
    private static volatile AE2Bridge bridge;
    private static volatile AE2Bridge testingBridge;

    private AE2BridgeBootstrap() {
    }

    public static void bootstrap() {
    }

    static AE2Bridge bridge() {
        AE2Bridge override = testingBridge;
        if (override != null) return override;

        AE2Bridge selected = bridge;
        if (selected != null) return selected;

        synchronized (AE2BridgeBootstrap.class) {
            if (bridge == null) bridge = load();
            return bridge;
        }
    }

    public static AE2Bridge selectForTesting(boolean ae2Loaded) {
        return ae2Loaded ? loadLoadedBridge() : UnavailableAE2Bridge.INSTANCE;
    }

    public static void installForTesting(AE2Bridge testingBridge) {
        AE2BridgeBootstrap.testingBridge = Objects.requireNonNull(testingBridge);
    }

    public static synchronized void resetForTesting() {
        testingBridge = null;
        bridge = null;
    }

    private static AE2Bridge load() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(AE2_MOD_ID) ? loadLoadedBridge() : UnavailableAE2Bridge.INSTANCE;
    }

    private static AE2Bridge loadLoadedBridge() {
        try {
            return (AE2Bridge) Class.forName(LOADED_BRIDGE).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to load AE2 integration", exception);
        }
    }
}
