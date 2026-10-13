package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.api.behaviors.GenericInternalInventory;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.helpers.externalstorage.GenericStackInvHandler;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternReturnInventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Gives AE2's native transfer adapters the actual capacity of each MMCR return slot.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(GenericStackInvHandler.class)
public abstract class GenericStackInvHandlerMixin {
    @WrapOperation(method = {"insert(ILnet/neoforged/neoforge/transfer/resource/Resource;ILnet/neoforged/neoforge/transfer/transaction/TransactionContext;)I",
            "getCapacityAsLong"}, at = @At(value = "INVOKE", target =
            "Lappeng/api/behaviors/GenericInternalInventory;getMaxAmount(Lappeng/api/stacks/AEKey;)J"))
    private long mmcr$keyCapacity(GenericInternalInventory inventory, AEKey key, Operation<Long> original,
                                  @Local(argsOnly = true, ordinal = 0) int slot) {
        return inventory instanceof PatternReturnInventory returns
                ? PatternReturnInventory.slotCapacity(returns, slot, key, key.getType())
                : original.call(inventory, key);
    }

    @WrapOperation(method = "getCapacityAsLong", at = @At(value = "INVOKE", target =
            "Lappeng/api/behaviors/GenericInternalInventory;getCapacity(Lappeng/api/stacks/AEKeyType;)J"))
    private long mmcr$typeCapacity(GenericInternalInventory inventory, AEKeyType type, Operation<Long> original,
                                   @Local(argsOnly = true, ordinal = 0) int slot) {
        return inventory instanceof PatternReturnInventory returns
                ? PatternReturnInventory.slotCapacity(returns, slot, null, type)
                : original.call(inventory, type);
    }
}
