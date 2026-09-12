package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import appeng.menu.slot.FakeSlot;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2OutputInterfaceBaseBlockEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Locks AE2 config write paths for MMCR output interface hosts so their UI stays
 * read-only while leaving input and stocking hosts untouched.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(AEBaseMenu.class)
public abstract class InterfaceMenuMixin {
    @Shadow
    public abstract Object getTarget();

    @Inject(method = "setFilter", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyOutputSetFilter(int slotIndex, ItemStack item, CallbackInfo callbackInfo) {
        if (mmcr$isOutputHost()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleFakeSlotAction", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyOutputFakeSlotAction(FakeSlot fakeSlot, InventoryAction action, CallbackInfo callbackInfo) {
        if (mmcr$isOutputHost()) {
            callbackInfo.cancel();
        }
    }

    private boolean mmcr$isOutputHost() {
        return getTarget() instanceof AE2OutputInterfaceBaseBlockEntity;
    }
}