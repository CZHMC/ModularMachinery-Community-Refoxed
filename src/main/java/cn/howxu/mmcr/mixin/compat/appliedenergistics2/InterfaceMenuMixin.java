package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.menu.implementations.InterfaceMenu;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2StockingInterfaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents the native amount sub-menu from being opened for stocking interfaces.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(InterfaceMenu.class)
public abstract class InterfaceMenuMixin {
    @Inject(method = "openSetAmountMenu", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyStockingAmountMenu(int configSlot, CallbackInfo callbackInfo) {
        InterfaceMenu menu = (InterfaceMenu) (Object) this;
        if (menu.getHost() instanceof AE2StockingInterfaceBlockEntity) {
            callbackInfo.cancel();
        }
    }
}
