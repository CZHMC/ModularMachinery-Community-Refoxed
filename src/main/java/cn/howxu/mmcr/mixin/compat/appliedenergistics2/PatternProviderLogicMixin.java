package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Propagates native pattern return-inventory drains to the MMCR pattern host.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(PatternProviderLogic.class)
public abstract class PatternProviderLogicMixin {
    @Shadow
    @Final
    private PatternProviderLogicHost host;

    @Unique
    private long[] mmcr$returnInventoryAmounts = new long[0];

    @Inject(method = "doWork", at = @At("HEAD"))
    private void mmcr$recordReturnInventoryAmount(CallbackInfoReturnable<Boolean> callback) {
        if (host instanceof PatternInterfaceBlockEntity) {
            PatternProviderLogic logic = (PatternProviderLogic) (Object) this;
            if (mmcr$returnInventoryAmounts.length != logic.getReturnInv().size()) {
                mmcr$returnInventoryAmounts = new long[logic.getReturnInv().size()];
            }
            for (int slot = 0; slot < mmcr$returnInventoryAmounts.length; slot++) {
                mmcr$returnInventoryAmounts[slot] = logic.getReturnInv().getAmount(slot);
            }
        }
    }

    @Inject(method = "doWork", at = @At("RETURN"))
    private void mmcr$notifyReturnInventoryDrain(CallbackInfoReturnable<Boolean> callback) {
        if (host instanceof PatternInterfaceBlockEntity patternHost
                && mmcr$returnInventoryDrained()) {
            patternHost.onNativeReturnInventoryDrained();
        }
    }

    @Unique
    private boolean mmcr$returnInventoryDrained() {
        PatternProviderLogic logic = (PatternProviderLogic) (Object) this;
        if (mmcr$returnInventoryAmounts.length != logic.getReturnInv().size()) return false;
        for (int slot = 0; slot < mmcr$returnInventoryAmounts.length; slot++) {
            if (logic.getReturnInv().getAmount(slot) < mmcr$returnInventoryAmounts[slot]) return true;
        }
        return false;
    }
}
