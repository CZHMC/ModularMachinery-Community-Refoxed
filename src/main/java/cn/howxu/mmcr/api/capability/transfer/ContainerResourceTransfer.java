package cn.howxu.mmcr.api.capability.transfer;

import cn.howxu.mmcr.util.IOType;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandlerUtil;
import net.neoforged.neoforge.transfer.resource.Resource;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Moves a container's resources in the requested manual direction.
 * The port handler must already be restricted to the selected tank.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class ContainerResourceTransfer {
    private ContainerResourceTransfer() {
    }

    public static <R extends Resource> int transfer(IOType ioType,
            ResourceHandler<R> container, ResourceHandler<R> port,
            TransactionContext transaction) {
        if (ioType == null || container == null || port == null) return 0;
        return ioType == IOType.INPUT
                ? ResourceHandlerUtil.move(container, port, resource -> true,
                        Integer.MAX_VALUE, transaction)
                : ResourceHandlerUtil.move(port, container, resource -> true,
                        Integer.MAX_VALUE, transaction);
    }

    /** World interaction: fill the container first, then drain it into an input port; null if neither succeeds. */
    public static <R extends Resource> Interaction<R> interact(IOType ioType,
            ResourceHandler<R> container, ResourceHandler<R> port,
            TransactionContext transaction) {
        if (ioType == null || container == null || port == null) return null;
        var extracted = ResourceHandlerUtil.moveFirst(port, container, resource -> true,
                Integer.MAX_VALUE, transaction);
        if (extracted != null) return new Interaction<>(extracted.resource(), extracted.amount(), true);
        if (ioType != IOType.INPUT) return null;
        var inserted = ResourceHandlerUtil.moveFirst(container, port, resource -> true,
                Integer.MAX_VALUE, transaction);
        return inserted == null ? null : new Interaction<>(inserted.resource(), inserted.amount(), false);
    }

    /**
     * Successful world transfer details for resource-specific feedback on root commit.
     *
     * @author howxu <dev@howxu.cn>
     */
    public record Interaction<R extends Resource>(R resource, int amount, boolean extract) {
    }
}
