package cn.howxu.mmcr;

import appeng.api.stacks.AEFluidKey;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PendingPatternQueue;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy v1 escrow is read-only ownership, refunded atomically rather than executed.
 *
 * @author howxu <dev@howxu.cn>
 */
public class PendingPatternQueueGameTest {
    public void rollbackRestoresGroupAndCounts(GameTestHelper helper) {
        var host = place(helper);
        AEItemKey iron = namedIron("refund-components");
        AEFluidKey water = AEFluidKey.of(Fluids.WATER);
        KeyCounter inputs = counter(iron, 5L);
        inputs.add(water, 1_000L);
        read(helper, host.pendingPatterns(), legacyData(helper, new KeyCounter[]{inputs}, outputs(), 7L));
        var before = host.pendingPatterns().groups();
        try (Transaction root = Transaction.openRoot()) {
            helper.assertTrue(host.pendingPatterns().refundNext(host, root), "Complete mixed group transfers provisionally");
            helper.assertTrue(host.pendingPatterns().isEmpty()
                            && host.getLogic().getReturnInv().getAvailableStacks().get(iron) == 35L,
                    "Both owners change inside the same root transaction");
        }
        helper.assertTrue(host.pendingPatterns().groups().equals(before)
                        && host.getLogic().getReturnInv().isEmpty(), "Root abort restores both owners exactly");
        refund(helper, host);
        helper.assertTrue(host.pendingPatterns().isEmpty()
                        && host.getLogic().getReturnInv().getAvailableStacks().get(iron) == 35L
                        && host.getLogic().getReturnInv().getAvailableStacks().get(water) == 7_000L,
                "Commit refunds every resource family without consuming inputs for a recipe");
        helper.succeed();
    }

    public void largeCompressedGroupRefundsAtomically(GameTestHelper helper) {
        var host = place(helper);
        AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
        long copies = 2_147_483_647L;
        read(helper, host.pendingPatterns(), legacyData(helper,
                new KeyCounter[]{counter(iron, 5L)}, outputs(), copies));
        refund(helper, host);
        helper.assertTrue(host.pendingPatterns().isEmpty()
                        && host.getLogic().getReturnInv().getAvailableStacks().get(iron) == 5L * copies,
                "One refund transfers the entire compressed group, not one copy or a shortened batch");
        try (Transaction root = Transaction.openRoot()) {
            helper.assertFalse(host.pendingPatterns().refundNext(host, root), "A removed group cannot refund twice");
            root.commit();
        }
        helper.succeed();
    }

    public void rootCallbackFailurePreservesCommittedOwnership(GameTestHelper helper) {
        var host = place(helper);
        var queue = new PendingPatternQueue(() -> { throw new IllegalStateException("Injected refund notification failure"); });
        AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
        read(helper, queue, legacyData(helper, new KeyCounter[]{counter(iron, 5L)}, outputs(), 1L));
        boolean failedNotification = false;
        try (Transaction root = Transaction.openRoot()) {
            helper.assertTrue(queue.refundNext(host, root), "Refund transfers before the root notification");
            root.commit();
        } catch (RuntimeException exception) {
            failedNotification = true;
        }
        helper.assertTrue(failedNotification && queue.committedRevision() == 1L && queue.isEmpty()
                        && host.getLogic().getReturnInv().getAvailableStacks().get(iron) == 5L,
                "A throwing post-commit notification cannot resurrect escrow or erase native returns");
        helper.succeed();
    }

    public void oldSaveHasEmptyPendingQueue(GameTestHelper helper) {
        var queue = new PendingPatternQueue();
        read(helper, queue, legacyData(helper, new KeyCounter[]{counter(AEItemKey.of(Items.IRON_INGOT), 1L)}, outputs(), 1L));
        read(helper, queue, new CompoundTag());
        helper.assertTrue(queue.isEmpty() && queue.groups().isEmpty(), "Saves predating escrow load no owned inputs");
        helper.succeed();
    }

    public void pendingQueueRoundTripPreservesComponentsAndLongCounts(GameTestHelper helper) {
        AEItemKey iron = namedIron("component-input");
        AEFluidKey water = AEFluidKey.of(Fluids.WATER);
        KeyCounter mixed = counter(iron, 3L);
        mixed.add(water, 1_000L);
        long copies = 2_147_483_647L;
        var queue = new PendingPatternQueue();
        read(helper, queue, legacyData(helper, new KeyCounter[]{mixed, counter(iron, 5L)}, outputs(), copies));
        var restored = new PendingPatternQueue();
        read(helper, restored, save(helper, queue));
        var group = restored.groups().getFirst();
        helper.assertTrue(group.copies() == copies && group.inputs(copies)[0].get(iron) == 3L * copies
                        && group.inputs(copies)[0].get(water) == 1_000L * copies
                        && group.inputs(copies)[1].get(iron) == 5L * copies,
                "Registry-aware save retains components, slots and exact long quantities");
        helper.succeed();
    }

    public void damagedOrFutureDataIsPreservedAndBlocked(GameTestHelper helper) {
        var host = place(helper);
        CompoundTag valid = legacyData(helper, new KeyCounter[]{counter(AEItemKey.of(Items.IRON_INGOT), 1L)}, outputs(), 1L);
        List<CompoundTag> damaged = new ArrayList<>();
        CompoundTag future = valid.copy();
        future.getCompoundOrEmpty("pending_patterns").putInt("version", 99);
        damaged.add(future);
        CompoundTag unknown = valid.copy();
        unknown.getCompoundOrEmpty("pending_patterns").getListOrEmpty("groups").getCompoundOrEmpty(0)
                .getListOrEmpty("inputs").getListOrEmpty(0).getCompoundOrEmpty(0)
                .putString("#t", "mmcr:unknown_resource_family");
        damaged.add(unknown);
        CompoundTag missingDefinition = valid.copy();
        missingDefinition.getCompoundOrEmpty("pending_patterns").getListOrEmpty("groups").getCompoundOrEmpty(0)
                .putString("definition", "unknown-definition");
        damaged.add(missingDefinition);
        CompoundTag missingOutput = valid.copy();
        missingOutput.getCompoundOrEmpty("pending_patterns").getListOrEmpty("groups").getCompoundOrEmpty(0)
                .getListOrEmpty("outputs").getCompoundOrEmpty(0).putString("#t", "mmcr:unknown_output_family");
        damaged.add(missingOutput);
        CompoundTag overflow = valid.copy();
        var overflowGroup = overflow.getCompoundOrEmpty("pending_patterns").getListOrEmpty("groups").getCompoundOrEmpty(0);
        overflowGroup.putLong("copies", Long.MAX_VALUE);
        overflowGroup.getListOrEmpty("inputs").getListOrEmpty(0).getCompoundOrEmpty(0).putLong("#", 2L);
        damaged.add(overflow);
        CompoundTag invalid = valid.copy();
        invalid.getCompoundOrEmpty("pending_patterns").getListOrEmpty("groups").getCompoundOrEmpty(0).putLong("copies", 0L);
        damaged.add(invalid);
        CompoundTag scalar = new CompoundTag();
        scalar.putString("pending_patterns", "opaque-owned-data");
        damaged.add(scalar);
        for (CompoundTag source : damaged) {
            CompoundTag expected = source.copy();
            read(helper, host.pendingPatterns(), source);
            source.putString("pending_patterns", "external mutation");
            try (Transaction root = Transaction.openRoot()) {
                helper.assertFalse(host.pendingPatterns().refundNext(host, root), "Unknown ownership cannot be partially refunded");
                root.commit();
            }
            helper.assertTrue(!host.pendingPatterns().isEmpty() && host.pendingPatterns().groups().isEmpty()
                            && host.getLogic().getReturnInv().isEmpty(), "Opaque ownership blocks admission without fabricating resources");
            var offered = counter(AEItemKey.of(Items.IRON_INGOT), 1L);
            var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                    List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)), outputs()), helper.getLevel());
            helper.assertFalse(host.craftingMachine().pushPattern(pattern, new KeyCounter[]{offered}, Direction.UP),
                    "A real standard request is rejected while opaque legacy ownership remains");
            helper.assertTrue(offered.get(AEItemKey.of(Items.IRON_INGOT)) == 1L, "Blocked admission leaves CPU counters untouched");
            helper.assertTrue(save(helper, host.pendingPatterns()).equals(expected), "Blocked payload is saved byte-for-byte");
        }
        helper.succeed();
    }

    static CompoundTag legacyData(GameTestHelper helper, KeyCounter[] inputs, List<GenericStack> outputs, long copies) {
        return legacyData(helper, inputs, outputs, copies, AEItemKey.of(Items.PAPER));
    }

    static CompoundTag legacyData(GameTestHelper helper, KeyCounter[] inputs, List<GenericStack> outputs,
                                  long copies, AEItemKey definition) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        var queue = output.child("pending_patterns");
        queue.putInt("version", 1);
        var group = queue.childrenList("groups").addChild();
        group.putLong("id", 1L);
        group.store("definition", AEItemKey.CODEC, definition);
        group.store("outputs", GenericStack.CODEC.listOf(), outputs);
        List<List<GenericStack>> slots = new ArrayList<>();
        for (KeyCounter input : inputs) {
            List<GenericStack> stacks = new ArrayList<>();
            for (var entry : input) stacks.add(new GenericStack(entry.getKey(), entry.getLongValue()));
            slots.add(stacks);
        }
        group.store("inputs", GenericStack.CODEC.listOf().listOf(), slots);
        group.putLong("copies", copies);
        group.putLong("first_age", 0L);
        group.putLong("last_age", 0L);
        return output.buildResult();
    }

    static void read(GameTestHelper helper, PendingPatternQueue queue, CompoundTag tag) {
        queue.read(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
    }

    static void refund(GameTestHelper helper, PatternInterfaceBlockEntity host) {
        try (Transaction root = Transaction.openRoot()) {
            helper.assertTrue(host.pendingPatterns().refundNext(host, root), "One complete legacy group fits in native returns");
            root.commit();
        }
    }

    private static CompoundTag save(GameTestHelper helper, PendingPatternQueue queue) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        queue.write(output, helper.getLevel().getGameTime());
        return output.buildResult();
    }

    private static PatternInterfaceBlockEntity place(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get().defaultBlockState());
        return helper.getBlockEntity(BlockPos.ZERO, PatternInterfaceBlockEntity.class);
    }

    private static KeyCounter counter(AEKey key, long amount) {
        KeyCounter counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }

    private static List<GenericStack> outputs() {
        return List.of(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 1L));
    }

    private static AEItemKey namedIron(String name) {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return AEItemKey.of(stack);
    }
}
