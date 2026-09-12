package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.InterfaceMenu;
import appeng.menu.slot.AppEngSlot;
import appeng.util.ConfigMenuInventory;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.GenericStack;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2OutputInterfaceBaseBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2StockingInterfaceBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the stocking interface's network mirror visible while making it read-only in AE2 menus,
 * and extends the same denial to MMCR output interface hosts so neither their storage nor their
 * config slots can be modified through the AE2 UI.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(AppEngSlot.class)
public abstract class AppEngSlotMixin {
    @Shadow
    protected abstract AEBaseMenu getMenu();

    @Shadow
    public abstract InternalInventory getInventory();

    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyLockedDisplayInsert(ItemStack stack, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (mmcr$isLockedDisplaySlot()) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private void mmcr$syncOrLockDisplay(ItemStack stack, CallbackInfo callbackInfo) {
        if (mmcr$syncStockingDisplay(stack)) {
            ((Slot) (Object) this).setChanged();
            callbackInfo.cancel();
            return;
        }
        if (mmcr$isOutputStorageSlot()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "initialize", at = @At("HEAD"), cancellable = true)
    private void mmcr$initializeLockedDisplay(ItemStack stack, CallbackInfo callbackInfo) {
        if (mmcr$syncStockingDisplay(stack)) {
            callbackInfo.cancel();
            return;
        }
        if (mmcr$isOutputStorageSlot()) {
            callbackInfo.cancel();
        }
    }

    private boolean mmcr$syncStockingDisplay(ItemStack stack) {
        if (getMenu() instanceof InterfaceMenu menu
                && menu.getHost() instanceof AE2StockingInterfaceBlockEntity host) {
            GenericStack mirrorStack = GenericStack.unwrapItemStack(stack);
            if (!(getInventory() instanceof ConfigMenuInventory wrapper)) return false;
            if (mirrorStack != null && wrapper.getDelegate() == host.getInterfaceLogic().getStorage()) {
                wrapper.getDelegate().setStack(((Slot) (Object) this).getSlotIndex(), mirrorStack);
                return true;
            }
        }
        return false;
    }

    @Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyLockedDisplayPickup(Player player, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (mmcr$isLockedDisplaySlot()) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyLockedDisplayRemove(int amount, CallbackInfoReturnable<ItemStack> callbackInfo) {
        if (mmcr$isLockedDisplaySlot()) {
            callbackInfo.setReturnValue(ItemStack.EMPTY);
        }
    }

    private boolean mmcr$isLockedDisplaySlot() {
        if (!(getMenu() instanceof InterfaceMenu menu)
                || !(getInventory() instanceof ConfigMenuInventory wrapper)) {
            return false;
        }
        Object host = menu.getHost();
        if (host instanceof AE2StockingInterfaceBlockEntity stocking) {
            return wrapper.getDelegate() == stocking.getInterfaceLogic().getStorage();
        }
        if (host instanceof AE2OutputInterfaceBaseBlockEntity output) {
            return wrapper.getDelegate() == output.getInterfaceLogic().getStorage();
        }
        return false;
    }

    private boolean mmcr$isOutputStorageSlot() {
        if (!(getMenu() instanceof InterfaceMenu menu)
                || !(menu.getHost() instanceof AE2OutputInterfaceBaseBlockEntity output)
                || !(getInventory() instanceof ConfigMenuInventory wrapper)) {
            return false;
        }
        return wrapper.getDelegate() == output.getInterfaceLogic().getStorage();
    }
}