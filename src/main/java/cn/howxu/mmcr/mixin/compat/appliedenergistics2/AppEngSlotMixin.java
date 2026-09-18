package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.InterfaceMenu;
import appeng.menu.slot.AppEngSlot;
import appeng.api.inventories.InternalInventory;
import appeng.api.stacks.GenericStack;
import appeng.util.ConfigMenuInventory;
import cn.howxu.mmcr.compat.appliedenergistics2.util.InterfaceMenuPolicy;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the stocking interface's network mirror visible while making it read-only in AE2 menus,
 * and protects MMCR output interface configuration slots while allowing the normal output cache
 * to be extracted through the AE2 UI.
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
        if (mmcr$isLockedDisplaySlot()
                || InterfaceMenuPolicy.isOutputStorageSlot(getMenu().getTarget(), getInventory())) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "set", at = @At("HEAD"), cancellable = true)
    private void mmcr$syncStockingDisplay(ItemStack stack, CallbackInfo callbackInfo) {
        if (mmcr$syncStockingDisplay(stack)) {
            ((Slot) (Object) this).setChanged();
            callbackInfo.cancel();
        } else if (InterfaceMenuPolicy.isOutputStorageSlot(getMenu().getTarget(), getInventory())
                && !mmcr$isOutputCacheRemoval(stack)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "initialize", at = @At("HEAD"), cancellable = true)
    private void mmcr$initializeStockingDisplay(ItemStack stack, CallbackInfo callbackInfo) {
        if (mmcr$syncStockingDisplay(stack)) {
            callbackInfo.cancel();
        }
    }

    @Unique
    private boolean mmcr$syncStockingDisplay(ItemStack stack) {
        var menu = getMenu();
        if (menu == null) return false;
        Object host = menu.getTarget();
        if (host instanceof StockingInterfaceBlockEntity stockingHost
                && getInventory() instanceof ConfigMenuInventory wrapper) {
            GenericStack mirrorStack = GenericStack.unwrapItemStack(stack);
            if (mirrorStack != null && wrapper.getDelegate() == stockingHost.getInterfaceLogic().getStorage()) {
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

    @Unique
    private boolean mmcr$isLockedDisplaySlot() {
        var menu = getMenu();
        if (menu == null || !(getInventory() instanceof ConfigMenuInventory wrapper)) {
            return false;
        }
        Object host = menu.getTarget();
        if (host instanceof StockingInterfaceBlockEntity stocking) {
            return wrapper.getDelegate() == stocking.getInterfaceLogic().getStorage();
        }
        if (menu instanceof InterfaceMenu) {
            return InterfaceMenuPolicy.isOutputStorageSlot(host, wrapper)
                    && !InterfaceMenuPolicy.isExtractableOutputStorageSlot(host, wrapper);
        }
        return false;
    }

    @Unique
    private boolean mmcr$isOutputCacheRemoval(ItemStack replacement) {
        Object host = getMenu().getTarget();
        if (!InterfaceMenuPolicy.isExtractableOutputStorageSlot(host, getInventory())
                || !(getInventory() instanceof ConfigMenuInventory wrapper)) {
            return false;
        }

        int slot = ((Slot) (Object) this).getSlotIndex();
        GenericStack current = wrapper.getDelegate().getStack(slot);
        GenericStack next = wrapper.convertToSuitableStack(replacement);
        if (current == null) return next == null;
        return next != null && current.what().equals(next.what()) && next.amount() <= current.amount()
                || next == null && replacement.isEmpty();
    }
}
