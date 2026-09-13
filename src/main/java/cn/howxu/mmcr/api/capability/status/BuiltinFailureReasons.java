package cn.howxu.mmcr.api.capability.status;

import cn.howxu.mmcr.MMCR;

import java.util.List;

/**
 * Failure reasons supplied by the core status model.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class BuiltinFailureReasons {
    public static final FailureReason UNKNOWN = reason("unknown", "gui.mmcr.failure.unknown", Integer.MIN_VALUE);
    public static final FailureReason MISSING_INPUT = reason(
            "missing_input", "gui.mmcr.controller.failure.missing_input", 400);
    public static final FailureReason MISSING_OUTPUT = reason(
            "missing_output", "gui.mmcr.controller.failure.missing_output", 100);
    public static final FailureReason MISSING_ENERGY = reason(
            "missing_energy", "gui.mmcr.controller.failure.missing_energy", 300);
    public static final FailureReason LEVEL_INSUFFICIENT = reason(
            "level_insufficient", "gui.mmcr.controller.failure.level_insufficient", 200);
    public static final FailureReason MODULE_CONNECTION = reason(
            "module_connection", "gui.mmcr.controller.failure.module_connection", 0);

    public static final FailureReason VERSION_INVALIDATED = reason(
            "version_invalidated", "gui.mmcr.controller.failure.structure_changed", 0);
    public static final FailureReason SMART_INTERFACE_CHANGED = reason(
            "smart_interface_changed", "gui.mmcr.controller.failure.smart_interface_changed", 0);
    public static final FailureReason RECIPE_SEARCH = unknown("recipe_search");
    public static final FailureReason RECIPE_SEARCH_EXCEPTION = reason(
            "recipe_search_exception", "gui.mmcr.controller.failure.recipe_search_exception", 0);
    public static final FailureReason RECIPE_LOAD = unknown("recipe_load");
    public static final FailureReason RECIPE_BEHAVIOR = unknown("recipe_behavior");
    public static final FailureReason RECIPE_START = unknown("recipe_start");
    public static final FailureReason PER_TICK = unknown("per_tick");
    public static final FailureReason FINISH = unknown("finish");
    public static final FailureReason INVALID_OUTPUTS = unknown("invalid_outputs");
    public static final FailureReason BEHAVIOR_BEFORE_FINISH = unknown("behavior_before_finish");
    public static final FailureReason BEHAVIOR_BEFORE_FINISH_CANCELLED = unknown("behavior_before_finish_cancelled");
    public static final FailureReason UNSUPPORTED_REQUEST = unknown("unsupported_request");
    public static final FailureReason WRONG_RESOURCE_TYPE = unknown("wrong_resource_type");
    public static final FailureReason INSUFFICIENT_VALUE = unknown("insufficient_value");
    public static final FailureReason SMART_VALUE = unknown("smart_value");
    public static final FailureReason OPERATION_FAILED_WITHOUT_STATUS = unknown("operation_failed_without_status");
    public static final FailureReason UNSAFE_OPERATION_PARALLELISM = unknown("unsafe_operation_parallelism");
    public static final FailureReason LOCAL_CAPACITY_CHANGED = unknown("local_capacity_changed");
    public static final FailureReason NO_TARGET = unknown("no_target");
    public static final FailureReason NO_WORK = unknown("no_work");
    public static final FailureReason TAG_MISMATCH = unknown("tag_mismatch");
    public static final FailureReason COMMIT_LOST_INPUT = unknown("commit_lost_input");
    public static final FailureReason COMMIT_LOST_OUTPUT = unknown("commit_lost_output");

    private static final List<FailureReason> ALL = List.of(
            UNKNOWN,
            MISSING_INPUT,
            MISSING_OUTPUT,
            MISSING_ENERGY,
            LEVEL_INSUFFICIENT,
            MODULE_CONNECTION,
            VERSION_INVALIDATED,
            SMART_INTERFACE_CHANGED,
            RECIPE_SEARCH,
            RECIPE_SEARCH_EXCEPTION,
            RECIPE_LOAD,
            RECIPE_BEHAVIOR,
            RECIPE_START,
            PER_TICK,
            FINISH,
            INVALID_OUTPUTS,
            BEHAVIOR_BEFORE_FINISH,
            BEHAVIOR_BEFORE_FINISH_CANCELLED,
            UNSUPPORTED_REQUEST,
            WRONG_RESOURCE_TYPE,
            INSUFFICIENT_VALUE,
            SMART_VALUE,
            OPERATION_FAILED_WITHOUT_STATUS,
            UNSAFE_OPERATION_PARALLELISM,
            LOCAL_CAPACITY_CHANGED,
            NO_TARGET,
            NO_WORK,
            TAG_MISMATCH,
            COMMIT_LOST_INPUT,
            COMMIT_LOST_OUTPUT);

    private BuiltinFailureReasons() {
    }

    public static void register() {
        ALL.forEach(FailureReasonRegistry::register);
    }

    private static FailureReason reason(String path, String translationKey, int priority) {
        return new FailureReason(MMCR.id(path), translationKey, priority);
    }

    private static FailureReason unknown(String path) {
        return reason(path, UNKNOWN.translationKey(), 0);
    }
}
