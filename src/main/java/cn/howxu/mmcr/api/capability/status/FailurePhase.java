package cn.howxu.mmcr.api.capability.status;

/**
 * Execution phase in which a failure was observed.
 *
 * @author howxu <dev@howxu.cn>
 */
public enum FailurePhase {
    CAPABILITY_PREPARE,
    CAPABILITY_COMMIT,
    REQUIREMENT_PLAN,
    LEVEL_CHECK,
    RECIPE_SEARCH,
    RECIPE_LOAD,
    RECIPE_START,
    PER_TICK,
    FINISH,
    RUNTIME,
    UNKNOWN
}
