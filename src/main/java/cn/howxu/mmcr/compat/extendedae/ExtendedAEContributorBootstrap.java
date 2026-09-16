package cn.howxu.mmcr.compat.extendedae;

import java.util.Objects;
import net.neoforged.fml.ModList;

/**
 * Lazily selects the optional ExtendedAE contributor without linking its implementation early.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class ExtendedAEContributorBootstrap {
    private static final String EXTENDED_AE_MOD_ID = "extendedae";
    private static final String LOADED_CONTRIBUTOR =
            "cn.howxu.mmcr.compat.extendedae.loaded.LoadedExtendedAEContributor";
    private static volatile ExtendedAEContributor contributor;
    private static volatile ExtendedAEContributor testingContributor;

    private ExtendedAEContributorBootstrap() {
    }

    public static ExtendedAEContributor contributor() {
        ExtendedAEContributor override = testingContributor;
        if (override != null) return override;

        ExtendedAEContributor selected = contributor;
        if (selected != null) return selected;

        synchronized (ExtendedAEContributorBootstrap.class) {
            if (contributor == null) contributor = load();
            return contributor;
        }
    }

    public static ExtendedAEContributor selectForTesting(boolean loaded) {
        return loaded ? loadLoadedContributor() : UnavailableExtendedAEContributor.INSTANCE;
    }

    public static void installForTesting(ExtendedAEContributor testingContributor) {
        ExtendedAEContributorBootstrap.testingContributor = Objects.requireNonNull(testingContributor);
    }

    public static synchronized void resetForTesting() {
        testingContributor = null;
        contributor = null;
    }

    private static ExtendedAEContributor load() {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(EXTENDED_AE_MOD_ID)
                ? loadLoadedContributor() : UnavailableExtendedAEContributor.INSTANCE;
    }

    private static ExtendedAEContributor loadLoadedContributor() {
        try {
            return (ExtendedAEContributor) Class.forName(LOADED_CONTRIBUTOR).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException exception) {
            return UnavailableExtendedAEContributor.INSTANCE;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to load ExtendedAE integration", exception);
        }
    }
}
