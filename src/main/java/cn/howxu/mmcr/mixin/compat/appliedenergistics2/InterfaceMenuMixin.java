package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import cn.howxu.mmcr.compat.appliedenergistics2.AE2InterfaceMenuPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Locks AE2 menu write paths for MMCR output interface hosts so their UI stays
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

    @Inject(method = "doAction", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyOutputMenuAction(ServerPlayer player, InventoryAction action, int slot, long id,
                                            CallbackInfo callbackInfo) {
        if (mmcr$isOutputHost()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyOutputQuickMove(Player player, int slot, CallbackInfoReturnable<ItemStack> callbackInfo) {
        if (mmcr$isOutputHost()) {
            callbackInfo.setReturnValue(ItemStack.EMPTY);
        }
    }

    private boolean mmcr$isOutputHost() {
        return AE2InterfaceMenuPolicy.isOutputHost(getTarget());
    }
}
