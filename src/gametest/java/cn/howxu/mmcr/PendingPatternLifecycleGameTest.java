package cn.howxu.mmcr;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PendingPatternQueue.OfferLimits;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.List;

/** Real saves and loot preserve owned inputs without expanding counts into dropped stacks.
 * @author howxu <dev@howxu.cn>
 */
public final class PendingPatternLifecycleGameTest {
    public static void ordinaryCarrierRoundTrip(GameTestHelper helper) {
        carrierRoundTrip(helper, "ae2_me_pattern_interface");
    }

    public static void extendedCarrierRoundTrip(GameTestHelper helper) {
        carrierRoundTrip(helper, "eae_me_extended_pattern_interface");
    }

    private static void carrierRoundTrip(GameTestHelper helper, String kind) {
        var host = place(helper, BlockPos.ZERO, kind);
        offer(helper, host);
        host.getLogic().getPatternInv().setItemDirect(0, Items.PAPER.getDefaultInstance());
        List<ItemStack> drops = Block.getDrops(host.getBlockState(), helper.getLevel(),
                host.getBlockPos(), host);
        helper.assertTrue(drops.size() == 1 && drops.getFirst().is(ModBlocks.BLOCKS.get(kind).get().asItem()),
                "Owned pending work stays in one ordinary block loot item");
        var carrier = drops.getFirst();
        helper.assertTrue(carrier.has(DataComponents.BLOCK_ENTITY_DATA), "Loot copies pending block entity data");
        // The native break path can invoke dropContents twice; it must not also discharge pending inputs.
        host.dropContents();
        host.dropContents();
        helper.assertTrue(host.pendingPatterns().totalCopies() == 1L, "Native content drops do not discharge pending work");
        var restored = place(helper, new BlockPos(2, 0, 0), kind);
        helper.assertTrue(BlockItem.updateCustomBlockEntityTag(helper.getLevel(), null, restored.getBlockPos(), carrier),
                "Vanilla block-item restoration loads the carried pending data");
        restored.applyComponentsFromItemStack(carrier);
        helper.assertTrue(restored.pendingPatterns().totalCopies() == 1L
                        && restored.pendingPatterns().groups().getFirst().inputs(1)[0].get(AEItemKey.of(Items.IRON_INGOT)) == 5L,
                "Replacing the carrier restores exactly the unprocessed material");
        helper.assertTrue(restored.getLogic().getPatternInv().isEmpty() && restored.linkedControllerPositions().isEmpty(),
                "Carrier does not clone native pattern slots or old controller links");
        helper.succeed();
    }

    public static void pendingSaveAndOldSave(GameTestHelper helper) {
        var host = place(helper, BlockPos.ZERO, "ae2_me_pattern_interface");
        offer(helper, host);
        helper.assertTrue(host.itemInputStorage().size() == 0 && host.fluidInputStorage().size() == 0
                        && host.getLogic().getReturnInv().isEmpty(),
                "Pending inputs are neither ordinary network inputs nor native return contents");
        helper.assertFalse(host.getUpdateTag(helper.getLevel().registryAccess()).contains("pending_patterns"),
                "Client block updates do not contain the private material escrow");
        var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        host.saveAdditional(saved);
        var restored = place(helper, new BlockPos(2, 0, 0), "ae2_me_pattern_interface");
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), saved.buildResult()));
        helper.assertTrue(restored.pendingPatterns().totalCopies() == 1L, "Ordinary block save restores owned work");
        var old = saved.buildResult().copy();
        old.remove("pending_patterns");
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), old));
        helper.assertTrue(restored.pendingPatterns().isEmpty(), "A legacy save has no pending owned inputs");
        helper.succeed();
    }

    private static PatternInterfaceBlockEntity place(GameTestHelper helper, BlockPos position, String kind) {
        helper.setBlock(position, ModBlocks.BLOCKS.get(kind).get().defaultBlockState());
        return helper.getBlockEntity(position, PatternInterfaceBlockEntity.class);
    }

    private static void offer(GameTestHelper helper, PatternInterfaceBlockEntity host) {
        var inputs = new KeyCounter();
        inputs.add(AEItemKey.of(Items.IRON_INGOT), 5L);
        var outputs = List.of(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 1L));
        var definition = AEItemKey.of(PatternDetailsHelper.encodeProcessingPattern(
                List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 5L)), outputs));
        try (var transaction = Transaction.openRoot()) {
            helper.assertTrue(host.pendingPatterns().offer(definition, outputs, new KeyCounter[]{inputs},
                    new OfferLimits(10, 10), helper.getLevel().getGameTime(), transaction), "Queue accepts a complete template");
            transaction.commit();
        }
    }
}
