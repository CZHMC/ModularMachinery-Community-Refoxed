package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamily;
import cn.howxu.mmcr.compat.ae2lt.LightningOutput;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestState;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.internal.runtime.PatternStartBatchReservation;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Virtual AE2 crafting machine that forwards processing patterns to linked MMCR controllers.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class PatternInterfaceCraftingMachine implements ICraftingMachine {
    private final PatternInterfaceBlockEntity host;

    public PatternInterfaceCraftingMachine(PatternInterfaceBlockEntity host) {
        this.host = host;
    }

    @Override
    public PatternContainerGroup getCraftingMachineInfo() {
        return PatternContainerGroup.nothing();
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolders, Direction ejectionDirection) {
        return pushBatchPattern(patternDetails, inputHolders, 1L, ejectionDirection);
    }

    public long maxBatchSize(IPatternDetails patternDetails) {
        if (!(patternDetails instanceof AEProcessingPattern pattern) || !hasSupportedInputs(pattern)) return 0L;
        List<MachineOutput> outputs = outputs(pattern);
        if (outputs == null) return 0L;
        long machineCap = host.maxPatternBatchSize();
        long recipeCap = host.maxRecipeBatchSize(outputs);
        long resourceCap = Math.min(batchResourceLimit(pattern.getSparseInputs()), batchResourceLimit(pattern.getOutputs()));
        return Math.min(resourceCap, Math.min(machineCap, recipeCap));
    }

    private static long batchResourceLimit(List<GenericStack> stacks) {
        Map<AEKey, Long> amounts = new HashMap<>();
        for (GenericStack stack : stacks) {
            if (stack == null) continue;
            if (stack.amount() <= 0L) return 0L;
            long previous = amounts.getOrDefault(stack.what(), 0L);
            if (stack.amount() > Long.MAX_VALUE - previous) return 0L;
            amounts.put(stack.what(), previous + stack.amount());
        }
        long limit = Long.MAX_VALUE;
        for (long amount : amounts.values()) limit = Math.min(limit, Long.MAX_VALUE / amount);
        return limit;
    }

    public boolean pushBatchPattern(IPatternDetails patternDetails, KeyCounter[] inputHolders, long batchSize,
                                    Direction ejectionDirection) {
        if (!(patternDetails instanceof AEProcessingPattern pattern)) return false;
        List<MachineOutput> outputs = outputs(pattern);
        if (batchSize <= 0L || outputs == null || !hasSupportedInputs(pattern)) return false;

        try {
            PatternRequestState originalRequest = new PatternRequestState(inputHolders, 1);
            List<LaneRequest> laneRequests = new ArrayList<>();
            List<AE2ResourceFamily<?>> families = AE2ResourceFamilies.all();
            PatternStartBatchReservation reservation = host.reservePatternStarts(outputs, batchSize, parallelism -> {
                PatternRequestState requestState = new PatternRequestState(
                        slice(inputHolders, parallelism, batchSize), families.size());
                List<RequestView<?>> views = new ArrayList<>();
                List<MachineCapability> capabilities = new ArrayList<>();
                for (var family : families) addRequestView(family, requestState, views, capabilities);
                laneRequests.add(new LaneRequest(views));
                return List.copyOf(capabilities);
            });
            if (reservation.status() != PatternStartBatchReservation.Status.RESERVED
                    || reservation.parallelism() != batchSize) return false;

            try (reservation) {
                return reservation.commit(transaction -> {
                    for (LaneRequest request : laneRequests) {
                        for (RequestView<?> view : request.views()) {
                            if (!view.accept(host, transaction)) throw ReturnCapacityException.INSTANCE;
                        }
                    }
                    originalRequest.acceptAll(transaction);
                });
            }
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static KeyCounter[] slice(KeyCounter[] inputHolders, long parallelism, long batchSize) {
        KeyCounter[] slice = new KeyCounter[inputHolders.length];
        for (int index = 0; index < inputHolders.length; index++) {
            KeyCounter source = inputHolders[index];
            KeyCounter target = new KeyCounter();
            for (var entry : source) {
                long amount = entry.getLongValue();
                if (amount < 0L || parallelism <= 0L || parallelism > batchSize) {
                    throw new IllegalArgumentException("Invalid pattern input slice");
                }
                long divisor = greatestCommonDivisor(parallelism, batchSize);
                long denominator = batchSize / divisor;
                if (amount % denominator != 0L) throw new IllegalArgumentException("Pattern inputs cannot be split exactly");
                target.add(entry.getKey(), Math.multiplyExact(amount / denominator, parallelism / divisor));
            }
            slice[index] = target;
        }
        return slice;
    }

    private static long greatestCommonDivisor(long first, long second) {
        while (second != 0L) {
            long remainder = first % second;
            first = second;
            second = remainder;
        }
        return first;
    }

    private <R> void addRequestView(AE2ResourceFamily<R> family, PatternRequestState state,
                                    List<RequestView<?>> views, List<MachineCapability> capabilities) {
        PatternRequestResourceStorage<R> request = family.patternRequestView(state);
        views.add(new RequestView<>(family, request));
        capabilities.add(family.capabilityView(host, request, IOType.INPUT, false));
    }

    /** @author howxu <dev@howxu.cn> */
    private record LaneRequest(List<RequestView<?>> views) {
    }

    /** @author howxu <dev@howxu.cn> */
    private record RequestView<R>(AE2ResourceFamily<R> family, PatternRequestResourceStorage<R> request) {
        boolean accept(PatternInterfaceBlockEntity host, TransactionContext transaction) {
            return request.accept(host.returnStorage(family), transaction);
        }
    }

    @Override
    public boolean acceptsPlans() {
        return true;
    }

    private static boolean hasSupportedInputs(AEProcessingPattern pattern) {
        return pattern.getSparseInputs().stream().filter(stack -> stack != null).allMatch(stack ->
                stack.amount() > 0L && AE2ResourceFamilies.supportsKey(stack.what()));
    }

    private static List<MachineOutput> outputs(AEProcessingPattern pattern) {
        List<MachineOutput> outputs = new ArrayList<>();
        Map<AEKey, Long> lightningAmounts = new HashMap<>();
        for (GenericStack stack : pattern.getOutputs()) {
            MachineOutput output = AE2ResourceFamilies.patternOutput(stack).orElse(null);
            if (output == null) return null;
            if (output instanceof LightningOutput) {
                long combined = lightningAmounts.getOrDefault(stack.what(), 0L) + stack.amount();
                if (combined > Integer.MAX_VALUE) return null;
                lightningAmounts.put(stack.what(), combined);
            }
            outputs.add(output);
        }
        return List.copyOf(outputs);
    }

    private static final class ReturnCapacityException extends RuntimeException {
        private static final ReturnCapacityException INSTANCE = new ReturnCapacityException();

        private ReturnCapacityException() {
            super(null, null, false, false);
        }
    }
}
