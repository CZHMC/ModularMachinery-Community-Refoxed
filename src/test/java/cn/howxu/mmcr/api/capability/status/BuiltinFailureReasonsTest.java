package cn.howxu.mmcr.api.capability.status;

import cn.howxu.mmcr.MMCR;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies registration and priority of the core failure reasons.
 *
 * @author howxu <dev@howxu.cn>
 */
class BuiltinFailureReasonsTest {
    @BeforeEach
    void clear_registry_before_test() {
        FailureReasonRegistry.clearForTesting();
    }

    @AfterEach
    void clear_registry_after_test() {
        FailureReasonRegistry.clearForTesting();
    }

    @Test
    void register_resolves_core_reasons_with_priorities() {
        BuiltinFailureReasons.register();

        assertThat(FailureReasonRegistry.find(MMCR.id("missing_input")))
                .isEqualTo(BuiltinFailureReasons.MISSING_INPUT);
        assertThat(FailureReasonRegistry.find(MMCR.id("missing_output")))
                .isEqualTo(BuiltinFailureReasons.MISSING_OUTPUT);
        assertThat(FailureReasonRegistry.find(MMCR.id("missing_energy")))
                .isEqualTo(BuiltinFailureReasons.MISSING_ENERGY);
        assertThat(FailureReasonRegistry.find(MMCR.id("level_insufficient")))
                .isEqualTo(BuiltinFailureReasons.LEVEL_INSUFFICIENT);
        assertThat(FailureReasonRegistry.find(MMCR.id("module_connection")))
                .isEqualTo(BuiltinFailureReasons.MODULE_CONNECTION);
        assertThat(BuiltinFailureReasons.MISSING_INPUT.priority()).isEqualTo(400);
        assertThat(BuiltinFailureReasons.MISSING_ENERGY.priority()).isEqualTo(300);
        assertThat(BuiltinFailureReasons.LEVEL_INSUFFICIENT.priority()).isEqualTo(200);
        assertThat(BuiltinFailureReasons.MISSING_OUTPUT.priority()).isEqualTo(100);
        assertThat(BuiltinFailureReasons.MODULE_CONNECTION.translationKey())
                .isEqualTo("gui.mmcr.controller.failure.module_connection");
    }
}
