package cn.howxu.mmcr.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Defines client-only MMCR configuration values.
 * @author howxu <dev@howxu.cn>
 */
public final class ClientConfig {
    public static final double DEFAULT_PREVIEW_RENDER_RADIUS = 64.0;
    public static final ModConfigSpec.DoubleValue PREVIEW_RENDER_RADIUS;
    public static final ModConfigSpec SPEC;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("preview");
        PREVIEW_RENDER_RADIUS = builder
                .comment("Maximum distance at which multiblock preview blocks are rendered")
                .defineInRange("render_radius", DEFAULT_PREVIEW_RENDER_RADIUS, 1.0, 512.0);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {
    }
}
