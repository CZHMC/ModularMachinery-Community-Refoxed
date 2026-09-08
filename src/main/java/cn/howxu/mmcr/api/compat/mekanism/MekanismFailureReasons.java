package cn.howxu.mmcr.api.compat.mekanism;

import cn.howxu.mmcr.MMCR;
import net.minecraft.resources.Identifier;

/**
 * Stable failure reason identifiers emitted by the optional Mekanism bridge.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismFailureReasons {
    public static final Identifier CHEMICAL_INPUT_MISSING = MMCR.id("chemical_input_missing");
    public static final Identifier CHEMICAL_OUTPUT_FULL = MMCR.id("chemical_output_full");
    public static final Identifier HEAT_INPUT_MISSING = MMCR.id("heat_input_missing");
    public static final Identifier HEAT_OUTPUT_FULL = MMCR.id("heat_output_full");

    private MekanismFailureReasons() {
    }
}
