package cn.howxu.mmcr.internal.event;

import appeng.core.ConventionTags;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.compat.mekanism.loaded.ChemicalPortBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Handles wrench dismantling for MMCR blocks.
 *
 * @author howxu <dev@howxu.cn>
 */
@EventBusSubscriber(modid = MMCR.MODID)
public final class WrenchDismantleHandler {
    private WrenchDismantleHandler() {}

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) return;

        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer serverPlayer) || player.isSpectator()
                || !player.isCrouching()
                || !player.getMainHandItem().is(ConventionTags.WRENCH)) return;

        Block block = event.getLevel().getBlockState(event.getPos()).getBlock();
        if (!ModBlocks.BLOCKS.values().stream().anyMatch(holder -> holder.isBound() && holder.get() == block)) return;

        if (event.getLevel().getBlockEntity(event.getPos()) instanceof ChemicalPortBlockEntity chemicalPort
                && chemicalPort.isRadioactive() && !chemicalPort.chemicalTank().isEmpty()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(serverPlayer.gameMode.destroyBlock(event.getPos())
                ? InteractionResult.SUCCESS : InteractionResult.FAIL);
    }
}
