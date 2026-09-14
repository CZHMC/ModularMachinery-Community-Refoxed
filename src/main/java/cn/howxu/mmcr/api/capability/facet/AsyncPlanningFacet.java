package cn.howxu.mmcr.api.capability.facet;

import cn.howxu.mmcr.api.capability.async.AsyncCapabilityOperation;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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
    default AsyncCapabilitySnapshot captureSnapshot() {
        requireServerThread("captureSnapshot");
        return captureSnapshotOnServerThread();
    }

    /**
     * Captures capability values after {@link #captureSnapshot()} has checked the server thread.
     *
     * @return worker-safe capability values
     */
    AsyncCapabilitySnapshot captureSnapshotOnServerThread();

    /**
     * Commits an operation against live storage. This method is main-thread-only.
     *
     * @param operation logical operation produced by {@link #plan(AsyncCapabilitySnapshot, AsyncCapabilityRequest)}
     * @param transaction transaction used to apply the operation
     * @return the operation result
     */
    default CapabilityResult commit(AsyncCapabilityOperation operation, TransactionContext transaction) {
        requireServerThread("commit");
        return commitOnServerThread(Objects.requireNonNull(operation, "operation"),
                Objects.requireNonNull(transaction, "transaction"));
    }

    /**
     * Commits an operation after {@link #commit(AsyncCapabilityOperation, TransactionContext)} has checked the server thread.
     *
     * @param operation logical operation produced from worker-safe values
     * @param transaction transaction used to apply the operation
     * @return the operation result
     */
    CapabilityResult commitOnServerThread(AsyncCapabilityOperation operation, TransactionContext transaction);

    private static void requireServerThread(String operation) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(operation + " requires the server thread");
        }
    }
}
