package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.client.gui.implementations.InterfaceScreen;
import appeng.menu.implementations.InterfaceMenu;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2StockingInterfaceBlockEntity;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hides amount controls on the AE2 screen used by stocking interfaces.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(value = InterfaceScreen.class, remap = false)
public abstract class InterfaceScreenMixin<C extends InterfaceMenu> {
    @Redirect(method = "updateBeforeRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/components/Button;visible:Z", opcode = 181))
    private void mmcr$hideStockingAmountButtons(Button button, boolean visible) {
        InterfaceMenu menu = (InterfaceMenu) ((AbstractContainerScreen<?>) (Object) this).getMenu();
        boolean stockingInterface = menu.getHost() instanceof AE2StockingInterfaceBlockEntity;
        button.visible = !stockingInterface && visible;
    }
}
