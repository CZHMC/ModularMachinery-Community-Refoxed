package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.slot.AppEngSlot;
import cn.howxu.mmcr.compat.appliedenergistics2.util.InterfaceMenuPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Locks AE2 menu write paths for MMCR output interface hosts while leaving the
 * normal output cache's extraction paths available.
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
        if (mmcr$isOutputHost() && !mmcr$isAllowedOutputAction(action, slot)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void mmcr$denyOutputQuickMove(Player player, int slot, CallbackInfoReturnable<ItemStack> callbackInfo) {
        if (mmcr$isOutputHost() && !mmcr$isExtractableOutputStorageSlot(slot)) {
            callbackInfo.setReturnValue(ItemStack.EMPTY);
        }
    }

    @Unique
    private boolean mmcr$isOutputHost() {
        return InterfaceMenuPolicy.isOutputHost(getTarget());
    }

    @Unique
    private boolean mmcr$isAllowedOutputAction(InventoryAction action, int slot) {
        if (!mmcr$isExtractableOutputStorageSlot(slot)) return false;
        return switch (action) {
            case FILL_ITEM, FILL_ITEM_MOVE_TO_PLAYER, FILL_ENTIRE_ITEM, FILL_ENTIRE_ITEM_MOVE_TO_PLAYER,
                    MOVE_REGION -> true;
            default -> false;
        };
    }

    @Unique
    private boolean mmcr$isExtractableOutputStorageSlot(int slot) {
        if (slot < 0) return false;
        AEBaseMenu menu = (AEBaseMenu) (Object) this;
        for (Slot candidate : menu.getSlots(SlotSemantics.STORAGE)) {
            if (candidate.index == slot && candidate instanceof AppEngSlot appEngSlot) {
                return InterfaceMenuPolicy.isExtractableOutputStorageSlot(getTarget(), appEngSlot.getInventory());
            }
        }
        return false;
    }
}
