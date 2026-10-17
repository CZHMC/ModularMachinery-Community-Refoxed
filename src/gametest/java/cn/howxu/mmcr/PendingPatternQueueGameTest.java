package cn.howxu.mmcr;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PendingPatternQueue;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PendingPatternQueue.GroupSnapshot;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PendingPatternQueue.OfferLimits;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestState;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Queue-only ownership, exact signatures, transactions and registry-aware persistence.
 *
 * @author howxu <dev@howxu.cn>
 */
public class PendingPatternQueueGameTest {
    private static final OfferLimits UNBOUNDED_COPIES = new OfferLimits(Long.MAX_VALUE, Long.MAX_VALUE);

    public void mergesOnlyIdenticalSlotTemplates(GameTestHelper helper) {
        var queue = new PendingPatternQueue();
        AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
        AEItemKey definition = AEItemKey.of(Items.PAPER);
        List<GenericStack> outputs = outputs();
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(queue.offer(definition, outputs, slots(counter(iron, 1L), counter(iron, 1L)),
                    UNBOUNDED_COPIES, 10L, transaction), "First exact slot template is accepted");
            helper.assertTrue(queue.offer(definition, outputs, slots(counter(iron, 1L), counter(iron, 1L)),
                    UNBOUNDED_COPIES, 14L, transaction), "Identical slots merge");
            helper.assertTrue(queue.offer(definition, outputs, slots(counter(iron, 2L), new KeyCounter()),
                    UNBOUNDED_COPIES, 14L, transaction), "Equal material totals in different slots form a separate group");
            helper.assertTrue(queue.offer(definition, outputs, slots(counter(namedIron("component"), 1L), counter(iron, 1L)),
                    UNBOUNDED_COPIES, 14L, transaction), "Component-different keys form a separate group");
            helper.assertTrue(queue.offer(definition, List.of(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 2L)),
                    slots(counter(iron, 1L), counter(iron, 1L)), UNBOUNDED_COPIES, 14L, transaction),
                    "Different promised quantities cannot merge");
            helper.assertTrue(queue.offer(namedIron("definition"), outputs, slots(counter(iron, 1L), counter(iron, 1L)),
                    UNBOUNDED_COPIES, 14L, transaction), "Different definitions cannot merge");
            transaction.commit();
        }
        GroupSnapshot first = queue.groups().getFirst();
        helper.assertTrue(queue.groups().size() == 5 && queue.totalCopies() == 6L && first.copies() == 2L,
                "Only the two identical requests share one compressed group");
        helper.assertTrue(first.firstTick() == 10L && first.lastTick() == 14L,
                "Merging preserves the oldest request while advancing the last arrival");
        helper.succeed();
    }

    public void offerOwnsDeepCopies(GameTestHelper helper) {
        var queue = new PendingPatternQueue();
        AEItemKey iron = namedIron("owned");
        KeyCounter original = counter(iron, 5L);
        KeyCounter[] inputs = slots(original);
        List<GenericStack> outputs = new ArrayList<>(outputs());
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(queue.offer(AEItemKey.of(Items.PAPER), outputs, inputs,
                    UNBOUNDED_COPIES, 1L, transaction), "Accept a copy without claiming caller counters");
            helper.assertTrue(original.get(iron) == 5L, "Offer does not consume caller-owned counters");
            transaction.commit();
        }
        original.clear();
        inputs[0] = counter(AEItemKey.of(Items.DIAMOND), 99L);
        outputs.clear();
        GroupSnapshot group = queue.groups().getFirst();
        KeyCounter[] view = group.inputs(1L);
        view[0].clear();
        helper.assertTrue(group.inputs()[0].get(iron) == 5L && group.outputs().equals(outputs()),
                "Neither caller mutation nor a mutable returned view can change owned material or promises");
        boolean outputsReadOnly = false;
        try {
            group.outputs().clear();
        } catch (UnsupportedOperationException exception) {
            outputsReadOnly = true;
        }
        helper.assertTrue(outputsReadOnly, "Output snapshot is immutable");
        helper.succeed();
    }

    public void rollbackRestoresGroupAndCounts(GameTestHelper helper) {
        AtomicInteger notifications = new AtomicInteger();
        var queue = new PendingPatternQueue(notifications::incrementAndGet);
        accept(helper, queue, AEItemKey.of(Items.PAPER), slots(counter(AEItemKey.of(Items.IRON_INGOT), 2L)), 10L);
        accept(helper, queue, AEItemKey.of(Items.BOOK), slots(counter(AEItemKey.of(Items.COPPER_INGOT), 3L)), 11L);
        List<GroupSnapshot> before = queue.groups();
        long revision = queue.committedRevision();
        try (Transaction root = Transaction.openRoot()) {
            queue.removeCopies(before.getFirst().id(), 1L, root);
            try (Transaction child = Transaction.open(root)) {
                queue.removeCopies(before.getLast().id(), 1L, child);
            }
            helper.assertTrue(queue.groups().size() == 1 && queue.groups().getFirst().id() == before.getLast().id(),
                    "Child abort restores only the child removal");
            helper.assertTrue(queue.offer(AEItemKey.of(Items.DIAMOND), outputs(), slots(counter(AEItemKey.of(Items.STONE), 1L)),
                    UNBOUNDED_COPIES, 12L, root), "An uncommitted replacement is visible inside the root");
        }
        helper.assertTrue(queue.groups().equals(before) && queue.totalCopies() == 2L,
                "Root abort restores exact group identities, counts and order");
        helper.assertTrue(queue.committedRevision() == revision && notifications.get() == 2,
                "Aborts do not notify or advance the irreversible commit revision");
        helper.succeed();
    }

    public void overflowRejectsWithoutMutation(GameTestHelper helper) {
        AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
        AEItemKey copper = AEItemKey.of(Items.COPPER_INGOT);
        AEItemKey definition = AEItemKey.of(Items.PAPER);
        var queue = new PendingPatternQueue();
        accept(helper, queue, definition, slots(counter(iron, Long.MAX_VALUE)), 1L);
        List<GroupSnapshot> original = queue.groups();
        long revision = queue.committedRevision();
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(!queue.offer(definition, outputs(), slots(counter(iron, Long.MAX_VALUE)),
                    UNBOUNDED_COPIES, 2L, transaction), "Multiplication overflow cannot merge");
            helper.assertTrue(!queue.offer(AEItemKey.of(Items.BOOK), outputs(), slots(counter(iron, 1L)),
                    UNBOUNDED_COPIES, 2L, transaction), "Same key cannot overflow across distinct groups");
            helper.assertTrue(queue.groups().equals(original), "Rejected offers leave the exact previous snapshot");
            transaction.commit();
        }
        helper.assertTrue(queue.committedRevision() == revision, "Rejected offers do not enlist the queue");
        var acrossSlots = new PendingPatternQueue();
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(!acrossSlots.offer(definition, outputs(), slots(counter(iron, Long.MAX_VALUE), counter(iron, 1L)),
                    UNBOUNDED_COPIES, 1L, transaction), "Same key cannot overflow across slots");
            helper.assertTrue(acrossSlots.isEmpty(), "Cross-slot rejection owns nothing");
            helper.assertTrue(queue.offer(AEItemKey.of(Items.BOOK), outputs(), slots(counter(copper, Long.MAX_VALUE)),
                    UNBOUNDED_COPIES, 2L, transaction), "Unrelated keys each retain their own exact long range");
            transaction.commit();
        }
        var promised = new PendingPatternQueue();
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(promised.offer(definition, List.of(new GenericStack(copper, Long.MAX_VALUE)),
                    slots(counter(iron, 1L)), UNBOUNDED_COPIES, 1L, transaction), "An exact long output promise is accepted");
            helper.assertTrue(!promised.offer(AEItemKey.of(Items.BOOK), List.of(new GenericStack(copper, 1L)),
                    slots(counter(iron, 1L)), UNBOUNDED_COPIES, 2L, transaction), "Output promises cannot overflow across groups");
            helper.assertTrue(promised.totalCopies() == 1L, "Output rejection cannot add material ownership");
            transaction.commit();
        }
        var maxCopies = compressed(helper, slots(counter(iron, 1L)), outputs(), Long.MAX_VALUE);
        GroupSnapshot maxGroup = maxCopies.groups().getFirst();
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(!maxCopies.offer(maxGroup.definition(), outputs(), slots(counter(iron, 1L)),
                    UNBOUNDED_COPIES, 101L, transaction), "Copy count itself cannot overflow when merging");
            helper.assertTrue(!maxCopies.offer(definition, outputs(), slots(counter(copper, 1L)),
                    UNBOUNDED_COPIES, 101L, transaction), "Total copies cannot overflow across different groups");
            transaction.commit();
        }
        helper.assertTrue(maxCopies.totalCopies() == Long.MAX_VALUE && maxCopies.groups().size() == 1
                        && maxCopies.groups().getFirst().inputs(Long.MAX_VALUE)[0].get(iron) == Long.MAX_VALUE,
                "Copy-overflow rejection preserves the exact valid long-sized compressed group");
        helper.succeed();
    }

    public void capacityAndDistinctKeyLimitsPreserveExistingWork(GameTestHelper helper) {
        AEItemKey definition = AEItemKey.of(Items.PAPER);
        AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
        var bounded = new PendingPatternQueue();
        try (Transaction transaction = Transaction.openRoot()) {
            for (int index = 0; index < 64; index++) {
                helper.assertTrue(bounded.offer(definition, outputs(), slots(counter(iron, index + 1L)),
                        UNBOUNDED_COPIES, 1L, transaction), "Different legal templates fill distinct groups");
            }
            helper.assertTrue(!bounded.offer(definition, outputs(), slots(counter(iron, 65L)),
                    UNBOUNDED_COPIES, 1L, transaction), "A new group cannot bypass the group bound");
            helper.assertTrue(bounded.offer(definition, outputs(), slots(counter(iron, 1L)),
                    UNBOUNDED_COPIES, 2L, transaction), "Existing groups may still merge at the distinct-group bound");
            helper.assertTrue(!bounded.offer(definition, outputs(), slots(counter(iron, 1L)),
                    new OfferLimits(1L, 1L), 2L, transaction), "Capacity shrink rejects without truncating accepted work");
            transaction.commit();
        }
        helper.assertTrue(bounded.totalCopies() == 65L && bounded.groups().getFirst().copies() == 2L,
                "Shrinking capacity retains every accepted copy");
        KeyCounter manyKeys = new KeyCounter();
        for (int index = 0; index < 256; index++) manyKeys.add(namedIron("key-" + index), 1L);
        var keys = new PendingPatternQueue();
        accept(helper, keys, definition, slots(manyKeys), 1L);
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(!keys.offer(definition, outputs(), slots(counter(namedIron("excess"), 1L)),
                    UNBOUNDED_COPIES, 2L, transaction), "A new component key cannot bypass the distinct-key bound");
            keys.removeCopies(keys.groups().getFirst().id(), 1L, transaction);
            helper.assertTrue(keys.offer(definition, outputs(), slots(counter(namedIron("excess"), 1L)),
                    UNBOUNDED_COPIES, 2L, transaction), "Removing owned inputs releases key capacity in the same transaction");
            transaction.commit();
        }
        var copies = new PendingPatternQueue();
        try (Transaction transaction = Transaction.openRoot()) {
            var limits = new OfferLimits(2L, 1L);
            helper.assertTrue(copies.offer(definition, outputs(), slots(counter(iron, 1L)), limits, 1L, transaction),
                    "First group uses one copy of capacity");
            helper.assertTrue(!copies.offer(definition, outputs(), slots(counter(iron, 1L)), limits, 1L, transaction),
                    "The group bound is independent of total free capacity");
            helper.assertTrue(copies.offer(definition, outputs(), slots(counter(iron, 2L)), limits, 1L, transaction),
                    "A distinct group can use the remaining total capacity");
            helper.assertTrue(!copies.offer(definition, outputs(), slots(counter(iron, 3L)), limits, 1L, transaction),
                    "A new group cannot bypass total copy capacity");
            transaction.commit();
        }
        helper.succeed();
    }

    public void largeCompressedGroupCanSplitAndRollback(GameTestHelper helper) {
        long copies = 2_147_483_647L;
        AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
        var queue = compressed(helper, slots(counter(iron, 5L)), outputs(), copies);
        GroupSnapshot original = queue.groups().getFirst();
        try (Transaction transaction = Transaction.openRoot()) {
            queue.removeCopies(original.id(), copies - 3L, transaction);
            GroupSnapshot remainder = queue.groups().getFirst();
            helper.assertTrue(remainder.copies() == 3L && remainder.inputs(3L)[0].get(iron) == 15L,
                    "Splitting a compressed long group does not expand per-request state");
        }
        helper.assertTrue(queue.groups().size() == 1 && queue.groups().getFirst().copies() == copies
                        && original.inputs(copies)[0].get(iron) == copies * 5L,
                "Rollback restores the entire exact compressed balance");
        try (Transaction transaction = Transaction.openRoot()) {
            queue.removeCopies(original.id(), copies - 3L, transaction);
            transaction.commit();
        }
        helper.assertTrue(queue.groups().getFirst().copies() == 3L && original.copies() == copies,
                "Committed partial removal leaves three copies and does not mutate previously returned snapshots");
        helper.succeed();
    }

    public void rootCallbackFailurePreservesCommittedOwnership(GameTestHelper helper) {
        PendingPatternQueue[] owner = new PendingPatternQueue[1];
        long[] observedRevision = {-1L};
        owner[0] = new PendingPatternQueue(() -> {
            observedRevision[0] = owner[0].committedRevision();
            throw new IllegalStateException("Injected queue notification failure");
        });
        var queue = owner[0];
        AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
        KeyCounter[] inputs = slots(counter(iron, 5L));
        var request = new PatternRequestState(inputs, 1);
        boolean failedNotification = false;
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(queue.offer(AEItemKey.of(Items.PAPER), outputs(), inputs,
                    UNBOUNDED_COPIES, 1L, transaction), "Queue participates in the same root as caller consumption");
            request.acceptAll(transaction);
            transaction.commit();
        } catch (RuntimeException exception) {
            failedNotification = true;
        }
        helper.assertTrue(failedNotification && observedRevision[0] == 1L && queue.committedRevision() == 1L,
                "Irreversible commit marker advances before a throwing notification");
        helper.assertTrue(queue.totalCopies() == 1L && queue.groups().getFirst().inputs()[0].get(iron) == 5L
                        && inputs[0].get(iron) == 0L,
                "Notification failure neither rolls back ownership nor restores caller counters");
        helper.succeed();
    }

    public void oldSaveHasEmptyPendingQueue(GameTestHelper helper) {
        var queue = new PendingPatternQueue();
        accept(helper, queue, AEItemKey.of(Items.PAPER), slots(counter(AEItemKey.of(Items.IRON_INGOT), 1L)), 1L);
        CompoundTag legacy = new CompoundTag();
        legacy.putString("unrelated", "untouched");
        queue.read(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), legacy));
        helper.assertTrue(queue.isEmpty() && queue.groups().isEmpty() && queue.totalCopies() == 0L
                        && legacy.getStringOr("unrelated", "").equals("untouched"),
                "A legacy save explicitly loads an empty queue without changing unrelated fields");
        helper.succeed();
    }

    public void pendingQueueRoundTripPreservesComponentsAndLongCounts(GameTestHelper helper) {
        AEItemKey iron = namedIron("component-input");
        AEFluidKey water = AEFluidKey.of(Fluids.WATER);
        KeyCounter mixed = counter(iron, 3L);
        mixed.add(water, 1_000L);
        List<GenericStack> promises = List.of(new GenericStack(namedIron("component-output"), 2L));
        long copies = 2_147_483_647L;
        var original = compressed(helper, slots(mixed, counter(iron, 5L)), promises, copies);
        CompoundTag saved = save(helper, original, 110L);
        var restored = load(helper, saved);
        restored.rebaseClock(5_000_000L);
        GroupSnapshot group = restored.groups().getFirst();
        KeyCounter[] material = group.inputs(copies);
        helper.assertTrue(group.copies() == copies && material.length == 2
                        && material[0].get(iron) == 3L * copies && material[0].get(water) == 1_000L * copies
                        && material[1].get(iron) == 5L * copies,
                "Registry-aware persistence keeps resource families, component keys, slot distribution and long counts");
        helper.assertTrue(group.definition().equals(original.groups().getFirst().definition())
                        && group.outputs().equals(promises) && group.id() == original.groups().getFirst().id(),
                "Definition, promises and stable group identity survive a complete round trip");
        helper.succeed();
    }

    public void reloadKeepsAgeWithoutOfflineAdvance(GameTestHelper helper) {
        var queue = new PendingPatternQueue();
        AEItemKey definition = AEItemKey.of(Items.PAPER);
        KeyCounter[] inputs = slots(counter(AEItemKey.of(Items.IRON_INGOT), 1L));
        accept(helper, queue, definition, inputs, 100L);
        accept(helper, queue, definition, inputs, 104L);
        CompoundTag saved = save(helper, queue, 110L);
        var restored = load(helper, saved);
        helper.assertTrue(save(helper, restored, 1_000_000L).equals(saved),
                "A second save before the first active tick preserves ages without using the new world clock");
        restored.rebaseClock(1_000_000L);
        GroupSnapshot group = restored.groups().getFirst();
        helper.assertTrue(group.firstTick() == 999_990L && group.lastTick() == 999_994L,
                "Reload rebuilds only active ages and never counts offline elapsed time");
        restored.rebaseClock(1_000_100L);
        helper.assertTrue(restored.groups().getFirst().firstTick() == group.firstTick(),
                "Clock rebasing occurs once, not on every server tick");
        try (Transaction transaction = Transaction.openRoot()) {
            restored.removeCopies(group.id(), 1L, transaction);
            transaction.commit();
        }
        helper.assertTrue(restored.groups().getFirst().firstTick() == group.firstTick(),
                "Partial execution never refreshes the oldest accepted request");
        helper.succeed();
    }

    public void damagedOrFutureDataIsPreservedAndBlocked(GameTestHelper helper) {
        var queue = new PendingPatternQueue();
        accept(helper, queue, AEItemKey.of(Items.PAPER), slots(counter(AEItemKey.of(Items.IRON_INGOT), 1L)), 1L);
        CompoundTag valid = save(helper, queue, 2L);
        List<CompoundTag> damaged = new ArrayList<>();
        CompoundTag future = valid.copy();
        future.getCompoundOrEmpty("pending_patterns").putInt("version", 99);
        damaged.add(future);
        CompoundTag zeroCopies = valid.copy();
        savedGroup(zeroCopies).putLong("copies", 0L);
        damaged.add(zeroCopies);
        CompoundTag unknownDefinition = valid.copy();
        savedGroup(unknownDefinition).putString("definition", "unknown-definition");
        damaged.add(unknownDefinition);
        CompoundTag unknownResource = valid.copy();
        savedGroup(unknownResource).getListOrEmpty("inputs").getListOrEmpty(0).getCompoundOrEmpty(0)
                .putString("#t", "mmcr:unknown_resource_family");
        damaged.add(unknownResource);
        CompoundTag unknownOutput = valid.copy();
        savedGroup(unknownOutput).getListOrEmpty("outputs").getCompoundOrEmpty(0)
                .putString("#t", "mmcr:unknown_output_family");
        damaged.add(unknownOutput);
        CompoundTag overflow = valid.copy();
        savedGroup(overflow).putLong("copies", Long.MAX_VALUE);
        savedGroup(overflow).getListOrEmpty("outputs").getCompoundOrEmpty(0).putLong("#", 2L);
        damaged.add(overflow);
        CompoundTag scalar = new CompoundTag();
        scalar.putString("pending_patterns", "opaque-owned-data");
        damaged.add(scalar);
        for (CompoundTag source : damaged) {
            CompoundTag expected = source.copy();
            PendingPatternQueue restored = load(helper, source);
            source.putString("pending_patterns", "external mutation after read");
            restored.rebaseClock(1_000L);
            helper.assertTrue(!restored.isEmpty() && restored.groups().isEmpty(),
                    "Unknown or corrupt ownership is not exposed as an empty usable queue");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertTrue(!restored.offer(AEItemKey.of(Items.PAPER), outputs(),
                        slots(counter(AEItemKey.of(Items.IRON_INGOT), 1L)), UNBOUNDED_COPIES, 1_000L, transaction),
                        "Opaque ownership blocks new admissions");
                transaction.commit();
            }
            helper.assertTrue(save(helper, restored, 2_000L).equals(expected),
                    "Saving blocked data preserves the entire original field, not a partial decode");
        }
        helper.succeed();
    }

    private static void accept(GameTestHelper helper, PendingPatternQueue queue, AEItemKey definition,
                               KeyCounter[] inputs, long tick) {
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(queue.offer(definition, outputs(), inputs, UNBOUNDED_COPIES, tick, transaction),
                    "Queue fixture accepts complete request");
            transaction.commit();
        }
    }

    /** Build an O(1)-group extreme fixture through the real persisted quantity, not billions of offers. */
    private static PendingPatternQueue compressed(GameTestHelper helper, KeyCounter[] inputs,
                                                   List<GenericStack> outputs, long copies) {
        var queue = new PendingPatternQueue();
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(queue.offer(namedIron("definition-components"), outputs, inputs,
                    UNBOUNDED_COPIES, 100L, transaction), "Seed compressed fixture with a real accepted template");
            transaction.commit();
        }
        CompoundTag saved = save(helper, queue, 100L);
        savedGroup(saved).putLong("copies", copies);
        PendingPatternQueue restored = load(helper, saved);
        restored.rebaseClock(100L);
        helper.assertTrue(restored.groups().size() == 1, "Compressed fixture decodes as one executable group");
        return restored;
    }

    private static CompoundTag savedGroup(CompoundTag tag) {
        return tag.getCompoundOrEmpty("pending_patterns").getListOrEmpty("groups").getCompoundOrEmpty(0);
    }

    private static CompoundTag save(GameTestHelper helper, PendingPatternQueue queue, long tick) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess());
        queue.write(output, tick);
        return output.buildResult();
    }

    private static PendingPatternQueue load(GameTestHelper helper, CompoundTag tag) {
        var queue = new PendingPatternQueue();
        queue.read(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tag));
        return queue;
    }

    private static KeyCounter counter(AEKey key, long amount) {
        KeyCounter counter = new KeyCounter();
        counter.add(key, amount);
        return counter;
    }

    private static KeyCounter[] slots(KeyCounter... slots) {
        return slots;
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
