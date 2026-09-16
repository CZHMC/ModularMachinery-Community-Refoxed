package cn.howxu.mmcr.compat.extendedae.mixin;

import appeng.client.gui.AEBaseScreen;
import cn.howxu.mmcr.compat.appliedenergistics2.InterfaceScreenTitles;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBaseBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import com.glodblock.github.extendedae.client.gui.GuiExInterface;
import com.glodblock.github.extendedae.container.ContainerExInterface;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies MMCR output and stocking policies to ExtendedAE interface screens.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(value = GuiExInterface.class, remap = false)
public abstract class ExtendedInterfaceScreenMixin {
    @Shadow
    private List<Button> amountButtons;

    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void mmcr$applyInterfacePolicies(CallbackInfo callbackInfo) {
        ContainerExInterface menu = (ContainerExInterface) ((AbstractContainerScreen<?>) (Object) this).getMenu();
        Object host = menu.getHost();
        if (host instanceof OutputInterfaceBaseBlockEntity || host instanceof StockingInterfaceBlockEntity) {
            amountButtons.forEach(button -> button.visible = false);
        }
        Component title = InterfaceScreenTitles.titleFor(host, Component.translatable("gui.extendedae.ex_interface"));
        ((AEBaseScreen<?>) (Object) this).getStyle().getText()
                .get(AEBaseScreen.TEXT_ID_DIALOG_TITLE)
                .setText(title);
    }
}
