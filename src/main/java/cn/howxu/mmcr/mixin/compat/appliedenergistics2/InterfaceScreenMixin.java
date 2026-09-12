package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.client.gui.implementations.InterfaceScreen;
import appeng.menu.implementations.InterfaceMenu;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2OutputInterfaceBaseBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2StockingInterfaceBlockEntity;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Hides amount controls on the AE2 screen for stocking and MMCR output interface hosts.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(value = InterfaceScreen.class, remap = false)
public abstract class InterfaceScreenMixin<C extends InterfaceMenu> {
    @Redirect(method = "updateBeforeRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/components/Button;visible:Z", opcode = 181))
    private void mmcr$hideLockedAmountButtons(Button button, boolean visible) {
        InterfaceMenu menu = (InterfaceMenu) ((AbstractContainerScreen<?>) (Object) this).getMenu();
        boolean lockedInterface = menu.getHost() instanceof AE2OutputInterfaceBaseBlockEntity
                || menu.getHost() instanceof AE2StockingInterfaceBlockEntity;
        button.visible = !lockedInterface && visible;
    }
}