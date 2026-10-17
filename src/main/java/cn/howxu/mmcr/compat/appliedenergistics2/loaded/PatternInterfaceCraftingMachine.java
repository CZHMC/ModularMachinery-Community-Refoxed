package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.compat.ae2lt.LightningOutput;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestState;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Accepts standard processing requests without changing the crafting CPU's accounting.
 * @author howxu <dev@howxu.cn>
 */
public final class PatternInterfaceCraftingMachine implements ICraftingMachine {
    private final PatternInterfaceBlockEntity host;
    private boolean accepting;

    public PatternInterfaceCraftingMachine(PatternInterfaceBlockEntity host) {
        this.host = host;
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        return PatternContainerGroup.nothing();
    }

    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs, Direction ejectionDirection) {
        if (accepting || host.isRemoved() || host.getLevel() == null || host.getLevel().isClientSide()) return false;
        var queue = host.pendingPatterns();
        long now = host.getLevel().getGameTime();
        if (!queue.canAttempt(now)) return false;
        accepting = true;
        long revision = queue.committedRevision();
        try {
            var limits = host.pendingScheduler().admissionLimits(pattern, inputs, now);
            if (limits == null) return false;
            PatternRequestState request = new PatternRequestState(inputs, 1);
            try (Transaction transaction = Transaction.openRoot()) {
                if (!queue.offer(pattern.getDefinition(), pattern.getOutputs(), inputs, limits, now, transaction)) return false;
                request.acceptAll(transaction);
                transaction.commit();
            }
            return true;
        } catch (RuntimeException exception) {
            // A root notification cannot undo ownership that has already committed.
            if (queue.committedRevision() != revision) {
                MMCR.LOG.warn("Pattern admission committed before notification failure at {}", host.getBlockPos(), exception);
                return true;
            }
            return false;
        } finally {
            accepting = false;
        }
    }

    @Override
    public boolean acceptsPlans() {
        return true;
    }

    static boolean validInputs(IPatternDetails details, KeyCounter[] inputs, Level level) {
        if (!(details instanceof AEProcessingPattern pattern) || inputs == null || level == null
                || outputs(pattern.getOutputs()) == null) return false;
        var expected = pattern.getInputs();
        if (inputs.length != expected.length || inputs.length == 0) return false;
        try {
            for (int index = 0; index < inputs.length; index++) {
                if (inputs[index] == null) return false;
                long units = 0L;
                for (var entry : inputs[index]) {
                    long amount = entry.getLongValue();
                    if (amount <= 0L || !AE2ResourceFamilies.supportsKey(entry.getKey())
                            || !expected[index].isValid(entry.getKey(), level)) return false;
                    long templateAmount = 0L;
                    for (var template : expected[index].getPossibleInputs()) {
                        if (entry.getKey().matches(template)) { templateAmount = template.amount(); break; }
                    }
                    if (templateAmount <= 0L || amount % templateAmount != 0L) return false;
                    units = Math.addExact(units, amount / templateAmount);
                }
                if (units != expected[index].getMultiplier()) return false;
            }
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    static List<MachineOutput> outputs(List<GenericStack> stacks) {
        List<MachineOutput> outputs = new ArrayList<>();
        Map<AEKey, Long> lightningAmounts = new HashMap<>();
        for (GenericStack stack : stacks) {
            if (stack == null || stack.amount() <= 0L) return null;
            MachineOutput output = AE2ResourceFamilies.patternOutput(stack).orElse(null);
            if (output == null) return null;
            if (output instanceof LightningOutput) {
                long previous = lightningAmounts.getOrDefault(stack.what(), 0L);
                if (stack.amount() > Integer.MAX_VALUE - previous) return null;
                lightningAmounts.put(stack.what(), previous + stack.amount());
            }
            outputs.add(output);
        }
        return outputs.isEmpty() ? null : List.copyOf(outputs);
    }
}
