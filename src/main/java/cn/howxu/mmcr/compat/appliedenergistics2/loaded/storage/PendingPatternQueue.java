package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.definitions.AEItems;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
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
 * Private, compressed ownership of complete standard pattern requests.
 * No material view is exposed to the network or ordinary machine input search.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class PendingPatternQueue extends SnapshotJournal<PendingPatternQueue.State> {
    private static final String SAVE_KEY = "pending_patterns";
    private static final int MAX_GROUPS = 64;
    private static final int MAX_INPUT_KEYS = 256;
    private static final int MAX_ATTEMPTS = 1024;

    private final Runnable onCommitted;
    private final List<GroupSnapshot> groups = new ArrayList<>();
    private long nextId = 1L;
    private long committedRevision;
    private boolean clockNeedsRebase;
    private Tag blockedData;
    private boolean hasAttemptTick;
    private long attemptTick;
    private int attempts;

    public PendingPatternQueue() {
        this(() -> {});
    }

    public PendingPatternQueue(Runnable onCommitted) {
        this.onCommitted = Objects.requireNonNull(onCommitted, "onCommitted");
    }

    /** Call once before admission validation; rejected requests also consume the budget. */
    public boolean canAttempt(long gameTime) {
        if (!hasAttemptTick || attemptTick != gameTime) {
            hasAttemptTick = true;
            attemptTick = gameTime;
            attempts = 0;
        }
        if (attempts >= MAX_ATTEMPTS) return false;
        attempts++;
        return true;
    }

    /** Does not consume the attempt budget or mutate the caller's counters. */
    public boolean offer(AEItemKey definition, List<GenericStack> outputs, KeyCounter[] oneCopyInputs,
                         OfferLimits limits, long gameTime, TransactionContext transaction) {
        if (blockedData != null || clockNeedsRebase || definition == null || outputs == null
                || oneCopyInputs == null || limits == null) return false;
        try {
            List<Map<AEKey, Long>> template = copyTemplate(oneCopyInputs);
            List<GenericStack> promised = copyOutputs(outputs);
            GroupSnapshot matching = null;
            for (GroupSnapshot group : groups) {
                if (group.definition.equals(definition) && group.outputs.equals(promised)
                        && group.template.equals(template)) {
                    matching = group;
                    break;
                }
            }
            long copies = matching == null ? 1L : Math.addExact(matching.copies, 1L);
            if (Math.addExact(totalCopies(), 1L) > limits.totalCopies || copies > limits.groupCopies) return false;
            if (matching == null && (groups.size() >= MAX_GROUPS || nextId == Long.MAX_VALUE)) return false;
            GroupSnapshot next = new GroupSnapshot(matching == null ? nextId : matching.id, definition,
                    promised, template, copies, matching == null ? gameTime : matching.firstTick, gameTime);
            List<GroupSnapshot> candidate = new ArrayList<>(groups);
            if (matching == null) candidate.add(next);
            else candidate.set(groups.indexOf(matching), next);
            validateTotals(candidate);
            updateSnapshots(transaction);
            groups.clear();
            groups.addAll(candidate);
            if (matching == null) nextId++;
            return true;
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return false;
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

    public void removeCopies(long groupId, long copies, TransactionContext transaction) {
        if (blockedData != null || clockNeedsRebase) throw new IllegalStateException("Pending queue is blocked");
        for (int index = 0; index < groups.size(); index++) {
            GroupSnapshot group = groups.get(index);
            if (group.id != groupId) continue;
            if (copies <= 0L || copies > group.copies) throw new IllegalArgumentException("Invalid copy count");
            updateSnapshots(transaction);
            if (copies == group.copies) groups.remove(index);
            else groups.set(index, new GroupSnapshot(group.id, group.definition, group.outputs, group.template,
                    group.copies - copies, group.firstTick, group.lastTick));
            return;
        }
        throw new IllegalArgumentException("Unknown pending group");
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
        nextId = 1L;
        clockNeedsRebase = false;
        blockedData = null;
        hasAttemptTick = false;
        attempts = 0;
        Dynamic<?> raw = input.read(SAVE_KEY, Codec.PASSTHROUGH).orElse(null);
        if (raw == null) return;
        blockedData = raw.convert(NbtOps.INSTANCE).getValue().copy();
        try {
            SavedQueue saved = raw.read(SavedQueue.CODEC).result().orElseThrow(
                    () -> new IllegalArgumentException("Invalid pending queue encoding"));
            if (saved.version != 1 || saved.groups.size() > MAX_GROUPS) return;
            List<GroupSnapshot> loaded = new ArrayList<>(saved.groups.size());
            Set<Long> ids = new HashSet<>();
            long loadedNextId = 1L;
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
                loadedNextId = Math.max(loadedNextId, group.id + 1L);
            }
            validateTotals(loaded);
            groups.addAll(loaded);
            nextId = loadedNextId;
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
        return new State(List.copyOf(groups), nextId);
    }

    @Override
    protected void revertToSnapshot(State snapshot) {
        groups.clear();
        groups.addAll(snapshot.groups);
        nextId = snapshot.nextId;
    }

    @Override
    protected void onRootCommit(State originalState) {
        // Deliberately not part of State: a notification failure cannot uncommit material ownership.
        committedRevision++;
        onCommitted.run();
    }

    private static List<Map<AEKey, Long>> copyTemplate(KeyCounter[] inputs) {
        List<Map<AEKey, Long>> template = new ArrayList<>(inputs.length);
        for (KeyCounter input : inputs) {
            if (input == null) throw new IllegalArgumentException("Null pending slot");
            Map<AEKey, Long> slot = new HashMap<>();
            for (var entry : input) {
                long amount = entry.getLongValue();
                if (amount < 0L) throw new IllegalArgumentException("Negative pending input");
                if (amount > 0L) slot.put(entry.getKey(), amount);
            }
            template.add(Map.copyOf(slot));
        }
        return List.copyOf(template);
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

    /** @author howxu <dev@howxu.cn> */
    public record OfferLimits(long totalCopies, long groupCopies) {
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
    protected record State(List<GroupSnapshot> groups, long nextId) {
    }

    // Lazy codecs keep pure budget tests independent of Minecraft registry initialization.
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
