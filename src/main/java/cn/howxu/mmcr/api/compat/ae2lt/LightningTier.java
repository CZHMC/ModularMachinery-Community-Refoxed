package cn.howxu.mmcr.api.compat.ae2lt;

import net.minecraft.resources.Identifier;

/** Native-independent lightning resource identities.
 * @author howxu <dev@howxu.cn>
 */
public enum LightningTier {
    HIGH_VOLTAGE("high_voltage"),
    EXTREME_HIGH_VOLTAGE("extreme_high_voltage");

    private final Identifier requirementId;

    LightningTier(String path) {
        requirementId = Identifier.fromNamespaceAndPath("ae2lt", path);
    }

    public Identifier requirementId() {
        return requirementId;
    }

    /** Resolves a canonical AE2LT resource identifier without loading native classes. */
    public static LightningTier fromId(Identifier id) {
        for (LightningTier tier : values()) {
            if (tier.requirementId.equals(id)) return tier;
        }
        throw new IllegalArgumentException("Unknown lightning tier: " + id);
    }
}
