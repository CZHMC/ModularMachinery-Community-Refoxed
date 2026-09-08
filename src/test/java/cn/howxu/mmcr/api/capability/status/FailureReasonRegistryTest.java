package cn.howxu.mmcr.api.capability.status;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies stable failure reason registration and lookup.
 *
 * @author howxu <dev@howxu.cn>
 */
class FailureReasonRegistryTest {
    @Test
    void duplicate_failure_reason_ids_are_rejected() {
        FailureReason reason = new FailureReason(Identifier.fromNamespaceAndPath("mmcr_test", "duplicate_reason"),
                "gui.mmcr.failure.duplicate_reason");
        FailureReasonRegistry.register(reason);
        assertThrows(IllegalArgumentException.class, () -> FailureReasonRegistry.register(reason));
        assertEquals(reason, FailureReasonRegistry.find(reason.id()));
    }

    @Test
    void execution_status_resolves_a_registered_reason_without_changing_details() {
        Identifier id = Identifier.fromNamespaceAndPath("mmcr_test", "registered_reason");
        FailureReason reason = new FailureReason(id, "gui.mmcr.failure.registered_reason");
        FailureReasonRegistry.register(reason);
        ExecutionStatus status = new ExecutionStatus(id, StatusSeverity.BLOCKED, id,
                Map.of("reason", id.toString()));

        assertEquals(reason, status.reason());
        assertEquals(id.toString(), status.details().get("reason"));
    }
}
