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
import cn.howxu.mmcr.internal.runtime.PatternStartReservation;
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
        if (!(patternDetails instanceof AEProcessingPattern pattern)) return false;
        List<MachineOutput> outputs = outputs(pattern);
        if (outputs == null || !hasSupportedInputs(pattern)) return false;

        try {
            PatternRequestState requestState = new PatternRequestState(inputHolders, 2);
            PatternRequestResourceStorage<ItemResource> itemRequest = AE2ResourceFamilies.ITEM.patternRequestView(requestState);
            PatternRequestResourceStorage<FluidResource> fluidRequest = AE2ResourceFamilies.FLUID.patternRequestView(requestState);
            List<MachineCapability> requestCapabilities = List.of(
                    new ItemBusCapability(itemRequest, IOType.INPUT),
                    new FluidHatchCapability(fluidRequest, IOType.INPUT));
            PatternStartReservation reservation = host.reservePatternStart(outputs, requestCapabilities);
            if (reservation.status() != PatternStartReservation.Status.RESERVED) return false;

            try (reservation) {
                return reservation.commit(transaction -> {
                    boolean itemsAccepted = itemRequest.accept(host.itemOutputStorage(), transaction);
                    boolean fluidsAccepted = fluidRequest.accept(host.fluidOutputStorage(), transaction);
                    if (!itemsAccepted || !fluidsAccepted) throw ReturnCapacityException.INSTANCE;
                });
            }
        } catch (RuntimeException exception) {
            return false;
        }
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
