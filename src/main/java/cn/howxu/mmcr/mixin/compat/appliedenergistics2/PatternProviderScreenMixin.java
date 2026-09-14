package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.implementations.PatternProviderScreen;
import appeng.menu.implementations.PatternProviderMenu;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the MMCR title only to pattern-provider screens hosted by the MMCR pattern interface.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(value = PatternProviderScreen.class, remap = false)
public abstract class PatternProviderScreenMixin {
    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void mmcr$replaceStyleTitle(CallbackInfo ci) {
        PatternProviderMenu menu = (PatternProviderMenu) ((AbstractContainerScreen<?>) (Object) this).getMenu();
        Component title = titleFor(menu.getTarget(), null);
        if (title != null) {
            ((AEBaseScreen<?>) (Object) this).getStyle().getText()
                    .get(AEBaseScreen.TEXT_ID_DIALOG_TITLE)
                    .setText(title);
        }
    }

    public static Component titleFor(Object host, Component fallback) {
        return host instanceof PatternInterfaceBlockEntity ? InterfaceScreenMixin.titleFor(host, fallback) : fallback;
    }
}
