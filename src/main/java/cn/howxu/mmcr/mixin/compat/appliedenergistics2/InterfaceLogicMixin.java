package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.helpers.InterfaceLogic;
import appeng.helpers.InterfaceLogicHost;
import appeng.util.ConfigInventory;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.NetworkOwnedInputHost;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tracks AE2-originated input cache inserts and limits automatic returns to that tracked amount.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(InterfaceLogic.class)
public abstract class InterfaceLogicMixin {
    @Shadow
    @Final
    protected InterfaceLogicHost host;

    @Shadow
    public abstract ConfigInventory getStorage();

    @Shadow
    @Final
    private GenericStack[] plannedWork;

    @Unique
    private GenericStack mmcr$planStorageBefore;

    @Unique
    private int mmcr$planAmount;

    @Inject(method = "updatePlan(I)V", at = @At("TAIL"))
    private void mmcr$limitInputReturnPlan(int slot, CallbackInfo callbackInfo) {
        if (!(host instanceof NetworkOwnedInputHost input)) return;

        GenericStack planned = plannedWork[slot];
        if (planned == null || planned.amount() >= 0L) {
            return;
        }

        long requested = -planned.amount();
        long returnable = Math.min(requested, input.networkOwnedAmount(slot, planned.what()));
        plannedWork[slot] = returnable == 0L
                ? null : new GenericStack(planned.what(), -returnable);
    }

    @Inject(method = "tryUsePlan(ILappeng/api/stacks/AEKey;I)Z", at = @At("HEAD"))
    private void mmcr$capturePlanStorage(int slot, AEKey what, int amount,
                                         CallbackInfoReturnable<Boolean> callbackInfo) {
        mmcr$planAmount = amount;
        mmcr$planStorageBefore = host instanceof NetworkOwnedInputHost
                ? getStorage().getStack(slot) : null;
    }

    @Inject(method = "tryUsePlan(ILappeng/api/stacks/AEKey;I)Z", at = @At("RETURN"))
    private void mmcr$recordPlanStorage(int slot, AEKey what, int amount,
                                        CallbackInfoReturnable<Boolean> callbackInfo) {
        if (host instanceof NetworkOwnedInputHost input) {
            if (mmcr$planAmount > 0) {
                mmcr$recordStorageIncrease(input, slot, mmcr$planStorageBefore);
            } else if (mmcr$planAmount < 0) {
                mmcr$recordStorageDecrease(input, slot, mmcr$planStorageBefore);
            }
        }
        mmcr$planStorageBefore = null;
        mmcr$planAmount = 0;
    }

    @Unique
    private void mmcr$recordStorageIncrease(NetworkOwnedInputHost input, int slot, GenericStack before) {
        GenericStack after = getStorage().getStack(slot);
        if (after == null) return;

        long beforeAmount = before != null && before.what().equals(after.what()) ? before.amount() : 0L;
        if (after.amount() > beforeAmount) {
            input.recordNetworkPull(slot, after.what(), after.amount() - beforeAmount);
        }
    }

    @Unique
    private void mmcr$recordStorageDecrease(NetworkOwnedInputHost input, int slot, GenericStack before) {
        if (before == null) return;

        GenericStack after = getStorage().getStack(slot);
        long afterAmount = after != null && before.what().equals(after.what()) ? after.amount() : 0L;
        long decrease = before.amount() - afterAmount;
        if (decrease > 0L) {
            input.recordNetworkReturn(slot, before.what(), decrease);
        }
    }
}
