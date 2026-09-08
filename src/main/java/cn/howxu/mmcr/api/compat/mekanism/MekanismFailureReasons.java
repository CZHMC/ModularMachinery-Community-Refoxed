package cn.howxu.mmcr.api.compat.mekanism;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.status.FailureReason;
import cn.howxu.mmcr.api.capability.status.FailureReasonRegistry;

import java.util.List;

/**
 * Stable failure reasons emitted by the optional Mekanism bridge.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MekanismFailureReasons {
    public static final FailureReason MEKANISM_UNAVAILABLE = reason("mekanism_unavailable");
    public static final FailureReason CHEMICAL_INPUT_MISSING = reason("chemical_input_missing");
    public static final FailureReason CHEMICAL_OUTPUT_BLOCKED = reason("chemical_output_blocked");
    public static final FailureReason CHEMICAL_TYPE_MISMATCH = reason("chemical_type_mismatch");
    public static final FailureReason CHEMICAL_RADIOACTIVITY_REJECTED = reason("chemical_radioactivity_rejected");
    public static final FailureReason HEAT_TEMPERATURE_INSUFFICIENT = reason("heat_temperature_insufficient");
    public static final FailureReason HEAT_OUTPUT_BLOCKED = reason("heat_output_blocked");

    private static final List<FailureReason> ALL = List.of(
            MEKANISM_UNAVAILABLE,
            CHEMICAL_INPUT_MISSING,
            CHEMICAL_OUTPUT_BLOCKED,
            CHEMICAL_TYPE_MISMATCH,
            CHEMICAL_RADIOACTIVITY_REJECTED,
            HEAT_TEMPERATURE_INSUFFICIENT,
            HEAT_OUTPUT_BLOCKED);

    private MekanismFailureReasons() {
    }

    public static void register() {
        ALL.forEach(FailureReasonRegistry::register);
    }

    private static FailureReason reason(String path) {
        return new FailureReason(MMCR.id(path), "gui.mmcr.failure." + path);
    }
}
