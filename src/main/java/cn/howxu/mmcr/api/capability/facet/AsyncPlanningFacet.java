package cn.howxu.mmcr.api.capability.facet;

import cn.howxu.mmcr.api.capability.CapabilityRequest;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityOperation;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import java.util.Optional;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Captures capability values on the main thread for planning on a worker thread.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface AsyncPlanningFacet extends CapabilityFacet {
    /**
     * Captures an immutable snapshot. This method and all capability storage access are main-thread-only.
     *
     * @return worker-safe capability values
     */
    AsyncCapabilitySnapshot captureSnapshot();

    /**
     * Plans a worker-safe operation from an immutable snapshot.
     *
     * @param snapshot captured capability values
     * @param request requested capability operation
     * @return a logical operation when this request is supported
     */
    Optional<AsyncCapabilityOperation> plan(AsyncCapabilitySnapshot snapshot, CapabilityRequest request);

    /**
     * Commits an operation against live storage. This method is main-thread-only.
     *
     * @param operation logical operation produced by {@link #plan(AsyncCapabilitySnapshot, CapabilityRequest)}
     * @param transaction transaction used to apply the operation
     * @return the operation result
     */
    CapabilityResult commit(AsyncCapabilityOperation operation, TransactionContext transaction);
}
