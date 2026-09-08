package cn.howxu.mmcr.compat.mekanism;

import cn.howxu.mmcr.api.compat.mekanism.MekanismFailureReasons;
import java.util.Objects;
import net.neoforged.fml.ModList;

/**
 * Lazily selects the optional Mekanism bridge without linking its implementation early.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismBridgeBootstrap {
    private static final String MEKANISM_MOD_ID = "mekanism";
    private static final String LOADED_BRIDGE = "cn.howxu.mmcr.compat.mekanism.loaded.LoadedMekanismBridge";
    private static volatile MekanismBridge bridge;
    private static volatile MekanismBridge testingBridge;

    private MekanismBridgeBootstrap() {
    }

    public static void bootstrap() {
        MekanismFailureReasons.register();
    }

    static MekanismBridge bridge() {
        MekanismBridge override = testingBridge;
        if (override != null) return override;

        MekanismBridge selected = bridge;
        if (selected != null) return selected;

        synchronized (MekanismBridgeBootstrap.class) {
            if (bridge == null) bridge = load();
            return bridge;
        }
    }

    static MekanismBridge selectForTesting(boolean mekanismLoaded) {
        return mekanismLoaded ? loadLoadedBridge() : UnavailableMekanismBridge.INSTANCE;
    }

    static void installForTesting(MekanismBridge testingBridge) {
        MekanismBridgeBootstrap.testingBridge = Objects.requireNonNull(testingBridge);
    }

    static synchronized void resetForTesting() {
        testingBridge = null;
        bridge = null;
    }

    private static MekanismBridge load() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(MEKANISM_MOD_ID) ? loadLoadedBridge() : UnavailableMekanismBridge.INSTANCE;
    }

    private static MekanismBridge loadLoadedBridge() {
        try {
            return (MekanismBridge) Class.forName(LOADED_BRIDGE).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to load Mekanism integration", exception);
        }
    }
}
