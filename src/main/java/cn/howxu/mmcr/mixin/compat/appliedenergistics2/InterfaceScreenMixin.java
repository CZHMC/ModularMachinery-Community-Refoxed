package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.menu.implementations.InterfaceMenu;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2StockingInterfaceBlockEntity;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Hides amount controls on the AE2 screen used by stocking interfaces.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(targets = "appeng.client.gui.implementations.InterfaceScreen")
public abstract class InterfaceScreenMixin<C extends InterfaceMenu> {
    @Shadow
    @Final
    private List<Button> amountButtons;

    @Shadow
    protected abstract C getMenu();

    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void mmcr$hideStockingAmountButtons(CallbackInfo callbackInfo) {
        if (getMenu().getHost() instanceof AE2StockingInterfaceBlockEntity) {
            for (Button button : amountButtons) {
                button.visible = false;
            }
        }
    }
}
