package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.pattern.AEProcessingPattern;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestState;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.internal.capability.FluidHatchCapability;
import cn.howxu.mmcr.internal.capability.ItemBusCapability;
import cn.howxu.mmcr.internal.runtime.PatternStartBatchReservation;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.List;

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
        long machineCap = host.maxPatternBatchSize();
        long recipeCap = host.maxRecipeBatchSize(outputs(pattern));
        return Math.min(machineCap, recipeCap);
    }

    public boolean pushBatchPattern(IPatternDetails patternDetails, KeyCounter[] inputHolders, long batchSize,
                                    Direction ejectionDirection) {
        if (!(patternDetails instanceof AEProcessingPattern pattern)) return false;
        List<MachineOutput> outputs = outputs(pattern);
        if (batchSize <= 0L || outputs == null || !hasSupportedInputs(pattern)) return false;

        try {
            PatternRequestState originalRequest = new PatternRequestState(inputHolders, 1);
            List<LaneRequest> laneRequests = new ArrayList<>();
            PatternStartBatchReservation reservation = host.reservePatternStarts(outputs, batchSize, parallelism -> {
                PatternRequestState requestState = new PatternRequestState(slice(inputHolders, parallelism, batchSize), 2);
                PatternRequestResourceStorage<ItemResource> itemRequest = AE2ResourceFamilies.ITEM.patternRequestView(requestState);
                PatternRequestResourceStorage<FluidResource> fluidRequest = AE2ResourceFamilies.FLUID.patternRequestView(requestState);
                laneRequests.add(new LaneRequest(itemRequest, fluidRequest));
                return List.of(new ItemBusCapability(itemRequest, IOType.INPUT),
                        new FluidHatchCapability(fluidRequest, IOType.INPUT));
            });
            if (reservation.status() != PatternStartBatchReservation.Status.RESERVED
                    || reservation.parallelism() != batchSize) return false;

            try (reservation) {
                return reservation.commit(transaction -> {
                    for (LaneRequest request : laneRequests) {
                        boolean itemsAccepted = request.itemRequest().accept(host.itemReturnStorage(), transaction);
                        boolean fluidsAccepted = request.fluidRequest().accept(host.fluidReturnStorage(), transaction);
                        if (!itemsAccepted || !fluidsAccepted) throw ReturnCapacityException.INSTANCE;
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
                long scaled = Math.multiplyExact(amount, parallelism);
                if (scaled % batchSize != 0L) throw new IllegalArgumentException("Pattern inputs cannot be split exactly");
                target.add(entry.getKey(), scaled / batchSize);
            }
            slice[index] = target;
        }
        return slice;
    }

    private record LaneRequest(PatternRequestResourceStorage<ItemResource> itemRequest,
                               PatternRequestResourceStorage<FluidResource> fluidRequest) {
    }

    @Override
    public boolean acceptsPlans() {
        return true;
    }

    private static boolean hasSupportedInputs(AEProcessingPattern pattern) {
        return pattern.getSparseInputs().stream().filter(stack -> stack != null).allMatch(stack ->
                stack.amount() > 0L && (stack.what() instanceof AEItemKey || stack.what() instanceof AEFluidKey));
    }

    private static List<MachineOutput> outputs(AEProcessingPattern pattern) {
        List<MachineOutput> outputs = new ArrayList<>();
        for (GenericStack stack : pattern.getOutputs()) {
            if (stack.amount() <= 0L || stack.amount() > Integer.MAX_VALUE) return null;
            int amount = (int) stack.amount();
            if (stack.what() instanceof AEItemKey item) {
                outputs.add(new MachineOutput.ItemOutput(item.toStack(amount), 1F));
            } else if (stack.what() instanceof AEFluidKey fluid) {
                outputs.add(new MachineOutput.FluidOutput(fluid.toStack(amount), 1F));
            } else {
                return null;
            }
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
