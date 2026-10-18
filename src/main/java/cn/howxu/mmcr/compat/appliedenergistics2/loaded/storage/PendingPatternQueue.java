package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.definitions.AEItems;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only legacy request ownership. Only whole-group transactional refunds are permitted.
 * No material view is exposed to ordinary machine input search.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class PendingPatternQueue extends SnapshotJournal<PendingPatternQueue.State> {
    private static final String SAVE_KEY = "pending_patterns";
    private static final int MAX_GROUPS = 64;
    private static final int MAX_INPUT_KEYS = 256;

    private final Runnable onCommitted;
    private final List<GroupSnapshot> groups = new ArrayList<>();
    private long committedRevision;
    private boolean clockNeedsRebase;
    private Tag blockedData;

    public PendingPatternQueue() {
        this(() -> {});
    }

    public PendingPatternQueue(Runnable onCommitted) {
        this.onCommitted = Objects.requireNonNull(onCommitted, "onCommitted");
    }

    /** Transfers exactly one complete legacy group, or leaves both owners unchanged. */
    public boolean refundNext(PatternInterfaceBlockEntity host, TransactionContext transaction) {
        if (blockedData != null || groups.isEmpty()) return false;
        GroupSnapshot group = groups.getFirst();
        Map<AEKey, Long> totals = new LinkedHashMap<>();
        for (Map<AEKey, Long> slot : group.template) {
            slot.forEach((key, amount) -> addExact(totals, key, Math.multiplyExact(amount, group.copies)));
        }
        var inventory = host.getLogic().getReturnInv();
        try (Transaction nested = Transaction.open(transaction)) {
            inventory.updateSnapshots(nested);
            for (var entry : totals.entrySet()) {
                if (inventory.insert(entry.getKey(), entry.getValue(), Actionable.MODULATE,
                        IActionSource.ofMachine(host)) != entry.getValue()) return false;
            }
            updateSnapshots(nested);
            groups.removeFirst();
            nested.commit();
            return true;
        }
    }

    public List<GroupSnapshot> groups() {
        return List.copyOf(groups);
    }

    /** Opaque damaged/future save data also counts as nonempty ownership. */
    public boolean isEmpty() {
        return groups.isEmpty() && blockedData == null;
    }

    public long totalCopies() {
        long total = 0L;
        for (GroupSnapshot group : groups) total = Math.addExact(total, group.copies);
        return total;
    }

    public long committedRevision() {
        return committedRevision;
    }

    public void write(ValueOutput output, long gameTime) {
        if (blockedData != null) {
            output.store(SAVE_KEY, Codec.PASSTHROUGH, new Dynamic<>(NbtOps.INSTANCE, blockedData.copy()));
            return;
        }
        List<SavedGroup> saved = new ArrayList<>(groups.size());
        for (GroupSnapshot group : groups) {
            List<List<GenericStack>> slots = new ArrayList<>(group.template.size());
            for (Map<AEKey, Long> slot : group.template) {
                slots.add(slot.entrySet().stream().map(entry -> new GenericStack(entry.getKey(), entry.getValue())).toList());
            }
            long firstAge = clockNeedsRebase ? -group.firstTick : age(gameTime, group.firstTick);
            long lastAge = clockNeedsRebase ? -group.lastTick : age(gameTime, group.lastTick);
            saved.add(new SavedGroup(group.id, group.definition, group.outputs, slots, group.copies, firstAge, lastAge));
        }
        output.store(SAVE_KEY, SavedQueue.CODEC, new SavedQueue(1, saved));
    }

    /** Decode atomically. Any invalid entry blocks the entire original payload, never a partial load. */
    public void read(ValueInput input) {
        groups.clear();
        clockNeedsRebase = false;
        blockedData = null;
        Dynamic<?> raw = input.read(SAVE_KEY, Codec.PASSTHROUGH).orElse(null);
        if (raw == null) return;
        blockedData = raw.convert(NbtOps.INSTANCE).getValue().copy();
        try {
            SavedQueue saved = raw.read(SavedQueue.CODEC).result().orElseThrow(
                    () -> new IllegalArgumentException("Invalid pending queue encoding"));
            if (saved.version != 1 || saved.groups.size() > MAX_GROUPS) return;
            List<GroupSnapshot> loaded = new ArrayList<>(saved.groups.size());
            Set<Long> ids = new HashSet<>();
            for (SavedGroup group : saved.groups) {
                if (group.id <= 0L || group.id == Long.MAX_VALUE || !ids.add(group.id)
                        || group.copies <= 0L || group.firstAge < 0L || group.lastAge < 0L
                        || group.lastAge > group.firstAge) return;
                List<Map<AEKey, Long>> template = new ArrayList<>(group.inputs.size());
                for (List<GenericStack> slot : group.inputs) {
                    Map<AEKey, Long> entries = new LinkedHashMap<>();
                    for (GenericStack stack : slot) {
                        if (stack.amount() <= 0L || entries.putIfAbsent(stack.what(), stack.amount()) != null) {
                            throw new IllegalArgumentException("Invalid pending slot");
                        }
                    }
                    template.add(Map.copyOf(entries));
                }
                loaded.add(new GroupSnapshot(group.id, group.definition, copyOutputs(group.outputs),
                        List.copyOf(template), group.copies, -group.firstAge, -group.lastAge));
            }
            validateTotals(loaded);
            groups.addAll(loaded);
            clockNeedsRebase = !groups.isEmpty();
            blockedData = null;
        } catch (RuntimeException exception) {
            // Retain every byte of the original field, including unrecognized resources/components.
            groups.clear();
        }
    }

    /** First active server tick establishes a new clock; offline time never advances ages. */
    public void rebaseClock(long gameTime) {
        if (!clockNeedsRebase) return;
        for (int index = 0; index < groups.size(); index++) {
            GroupSnapshot group = groups.get(index);
            groups.set(index, new GroupSnapshot(group.id, group.definition, group.outputs, group.template,
                    group.copies, subtractAge(gameTime, -group.firstTick), subtractAge(gameTime, -group.lastTick)));
        }
        clockNeedsRebase = false;
    }

    @Override
    protected State createSnapshot() {
        return new State(List.copyOf(groups));
    }

    @Override
    protected void revertToSnapshot(State snapshot) {
        groups.clear();
        groups.addAll(snapshot.groups);
    }

    @Override
    protected void onRootCommit(State originalState) {
        // Deliberately not part of State: a notification failure cannot uncommit material ownership.
        committedRevision++;
        onCommitted.run();
    }

    private static List<GenericStack> copyOutputs(List<GenericStack> outputs) {
        if (outputs.isEmpty()) throw new IllegalArgumentException("Empty pending outputs");
        for (GenericStack output : outputs) {
            if (output == null || output.what() == null || output.amount() <= 0L) {
                throw new IllegalArgumentException("Invalid pending output");
            }
        }
        // GenericStack and AEKey are immutable; only containers and counters require copying.
        return List.copyOf(outputs);
    }

    private static void validateTotals(List<GroupSnapshot> candidate) {
        Map<AEKey, Long> inputs = new HashMap<>();
        Map<AEKey, Long> outputs = new HashMap<>();
        long copies = 0L;
        for (GroupSnapshot group : candidate) {
            requireKnownKey(group.definition);
            copies = Math.addExact(copies, group.copies);
            for (Map<AEKey, Long> slot : group.template) {
                slot.forEach((key, amount) -> {
                    requireKnownKey(key);
                    addExact(inputs, key, Math.multiplyExact(amount, group.copies));
                });
            }
            for (GenericStack output : group.outputs) {
                requireKnownKey(output.what());
                addExact(outputs, output.what(), Math.multiplyExact(output.amount(), group.copies));
            }
        }
        if (inputs.size() > MAX_INPUT_KEYS) throw new IllegalArgumentException("Too many pending input keys");
    }

    private static void requireKnownKey(AEKey key) {
        // AEKey's native codec converts decode failures into a successful missing-content item.
        if (AEItems.MISSING_CONTENT.is(key)) throw new IllegalArgumentException("Unresolved pending resource");
    }

    private static void addExact(Map<AEKey, Long> totals, AEKey key, long amount) {
        totals.put(key, Math.addExact(totals.getOrDefault(key, 0L), amount));
    }

    private static long age(long now, long tick) {
        if (now <= tick) return 0L;
        try {
            return Math.subtractExact(now, tick);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long subtractAge(long now, long age) {
        try {
            return Math.subtractExact(now, age);
        } catch (ArithmeticException exception) {
            return Long.MIN_VALUE;
        }
    }

    /** Immutable identity/template; each input view is freshly allocated.
     * @author howxu <dev@howxu.cn>
     */
    public static final class GroupSnapshot {
        private final long id;
        private final AEItemKey definition;
        private final List<GenericStack> outputs;
        private final List<Map<AEKey, Long>> template;
        private final long copies;
        private final long firstTick;
        private final long lastTick;

        private GroupSnapshot(long id, AEItemKey definition, List<GenericStack> outputs,
                              List<Map<AEKey, Long>> template, long copies, long firstTick, long lastTick) {
            this.id = id;
            this.definition = definition;
            this.outputs = outputs;
            this.template = template;
            this.copies = copies;
            this.firstTick = firstTick;
            this.lastTick = lastTick;
        }

        public long id() { return id; }
        public AEItemKey definition() { return definition; }
        public List<GenericStack> outputs() { return outputs; }
        public long copies() { return copies; }
        public long firstTick() { return firstTick; }
        public long lastTick() { return lastTick; }

        public KeyCounter[] inputs() {
            return inputs(1L);
        }

        public KeyCounter[] inputs(long copies) {
            if (copies <= 0L || copies > this.copies) throw new IllegalArgumentException("Invalid copy count");
            KeyCounter[] inputs = new KeyCounter[template.size()];
            for (int index = 0; index < inputs.length; index++) {
                KeyCounter counter = new KeyCounter();
                template.get(index).forEach((key, amount) -> counter.add(key, Math.multiplyExact(amount, copies)));
                inputs[index] = counter;
            }
            return inputs;
        }
    }

    /** @author howxu <dev@howxu.cn> */
    protected record State(List<GroupSnapshot> groups) {
    }

    // Preserve the original registry-aware v1 escrow schema.
    /** @author howxu <dev@howxu.cn> */
    private record SavedQueue(int version, List<SavedGroup> groups) {
        private static final Codec<SavedQueue> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("version").forGetter(SavedQueue::version),
                SavedGroup.CODEC.listOf().fieldOf("groups").forGetter(SavedQueue::groups)
        ).apply(instance, SavedQueue::new));
    }

    /** @author howxu <dev@howxu.cn> */
    private record SavedGroup(long id, AEItemKey definition, List<GenericStack> outputs,
                              List<List<GenericStack>> inputs, long copies, long firstAge, long lastAge) {
        private static final Codec<SavedGroup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("id").forGetter(SavedGroup::id),
                AEItemKey.CODEC.fieldOf("definition").forGetter(SavedGroup::definition),
                GenericStack.CODEC.listOf().fieldOf("outputs").forGetter(SavedGroup::outputs),
                GenericStack.CODEC.listOf().listOf().fieldOf("inputs").forGetter(SavedGroup::inputs),
                Codec.LONG.fieldOf("copies").forGetter(SavedGroup::copies),
                Codec.LONG.fieldOf("first_age").forGetter(SavedGroup::firstAge),
                Codec.LONG.fieldOf("last_age").forGetter(SavedGroup::lastAge)
        ).apply(instance, SavedGroup::new));
    }
}
