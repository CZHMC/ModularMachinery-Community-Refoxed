package cn.howxu.mmcr.api.capability.transfer;

import cn.howxu.mmcr.api.capability.MachineCapability;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.resource.Resource;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Capability-level automatic input/output transfer contract.
 *
 * @author howxu <dev@howxu.cn>
 */
@FunctionalInterface
public interface TransferPolicy {
    TransferResult transfer(TransferContext context);

    default boolean hasWork(MachineCapability capability) {
        return true;
    }

    default boolean hasAdjacentTarget(MachineCapability capability, Direction side) {
        return true;
    }

    default TransferResult eject(TransferContext context) {
        return transfer(context.asEjection());
    }

    default TransferResult eject(TransferContext context, @Nullable Resource resource) {
        return transfer(context.asEjection(resource));
    }

    default TransferResult eject(TransferContext context, @Nullable Resource resource, long limit) {
        return transfer(context.asEjection(resource, limit));
    }

    default List<Resource> ejectionResources(MachineCapability capability) {
        return List.of();
    }
}
