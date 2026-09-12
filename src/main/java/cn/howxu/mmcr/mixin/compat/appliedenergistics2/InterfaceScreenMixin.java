package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.client.gui.implementations.InterfaceScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.implementations.InterfaceMenu;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBaseBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Customizes the title and amount controls on the AE2 screen for MMCR interface hosts.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(value = InterfaceScreen.class, remap = false)
public abstract class InterfaceScreenMixin<C extends InterfaceMenu> {
    @Shadow(remap = false)
    @Final
    @Mutable
    protected Component title;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mmcr$replaceTitle(C menu, Inventory playerInventory, Component originalTitle, ScreenStyle style,
                                    CallbackInfo ci) {
        if (menu.getHost() instanceof IOPortBlockEntity host) {
            this.title = Component.translatable("container.mmcr." + host.kind().id());
        }
    }

    @Redirect(method = "updateBeforeRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/components/Button;visible:Z", opcode = 181))
    private void mmcr$hideLockedAmountButtons(Button button, boolean visible) {
        InterfaceMenu menu = (InterfaceMenu) ((AbstractContainerScreen<?>) (Object) this).getMenu();
        boolean lockedInterface = menu.getHost() instanceof OutputInterfaceBaseBlockEntity
                || menu.getHost() instanceof StockingInterfaceBlockEntity;
        button.visible = !lockedInterface && visible;
    }
}
