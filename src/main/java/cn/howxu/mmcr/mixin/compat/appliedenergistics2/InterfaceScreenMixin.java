package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.implementations.InterfaceScreen;
import appeng.menu.implementations.InterfaceMenu;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBaseBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Customizes the title and amount controls on the AE2 screen for MMCR interface hosts.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(value = InterfaceScreen.class, remap = false)
public abstract class InterfaceScreenMixin<C extends InterfaceMenu> {
    @ModifyArgs(method = "<init>", at = @At(value = "INVOKE", target =
            "Lappeng/client/gui/implementations/UpgradeableScreen;<init>(Lappeng/menu/implementations/UpgradeableMenu;"
                    + "Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/network/chat/Component;"
                    + "Lappeng/client/gui/style/ScreenStyle;)V"))
    private static void mmcr$replaceTitle(Args args) {
        InterfaceMenu menu = args.get(0);
        var host = menu.getHost();
        Component title = titleFor(host, null);
        if (title != null) args.set(2, title);
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void mmcr$replaceStyleTitle(CallbackInfo ci) {
        InterfaceMenu menu = (InterfaceMenu) ((AbstractContainerScreen<?>) (Object) this).getMenu();
        var host = menu.getHost();
        Component displayTitle = titleFor(host, Component.translatable("gui.ae2.Interface"));
        ((AEBaseScreen<?>) (Object) this).getStyle().getText()
                .get(AEBaseScreen.TEXT_ID_DIALOG_TITLE)
                .setText(displayTitle);
    }

    @Redirect(method = "updateBeforeRender", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/components/Button;visible:Z", opcode = 181))
    private void mmcr$hideLockedAmountButtons(Button button, boolean visible) {
        InterfaceMenu menu = (InterfaceMenu) ((AbstractContainerScreen<?>) (Object) this).getMenu();
        boolean lockedInterface = menu.getHost() instanceof OutputInterfaceBaseBlockEntity
                || menu.getHost() instanceof StockingInterfaceBlockEntity;
        button.visible = !lockedInterface && visible;
    }

    public static Component titleFor(Object host, Component fallback) {
        return host instanceof IOPortBlockEntity port
                ? Component.translatable("container.mmcr." + port.kind().id())
                : fallback;
    }
}
