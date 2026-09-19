package cn.howxu.mmcr.mixin.compat.appliedenergistics2;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternInterfaceCraftingMachine;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternProviderLogicBatchAccess;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceHost;
import java.util.Map;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Batches only processing patterns selected for an MMCR pattern-provider host.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin(CraftingCpuLogic.class)
public abstract class CraftingCpuLogicMixin {
    @Shadow private ExecutingCraftingJob job;
    @Shadow @Final private ListCraftingInventory inventory;
    @Shadow @Final CraftingCPUCluster cluster;

    /**
     * @author howxu <dev@howxu.cn>
     * @reason MMCR factory interfaces atomically admit multiple processing operations.
     */
    @Overwrite
    public int executeCrafting(int maxPatterns, CraftingService craftingService, IEnergyService energyService,
                               Level level) {
        if (job == null) return 0;
        ExecutingCraftingJobAccessor jobAccess = (ExecutingCraftingJobAccessor) job;
        int pushedPatterns = 0;
        var iterator = jobAccess.mmcr$tasks().entrySet().iterator();
        taskLoop: while (iterator.hasNext()) {
            Map.Entry<appeng.api.crafting.IPatternDetails, Object> task = iterator.next();
            ExecutingCraftingJobTaskProgressAccessor progress =
                    (ExecutingCraftingJobTaskProgressAccessor) task.getValue();
            if (progress.mmcr$value() <= 0L) {
                iterator.remove();
                continue;
            }

            var details = task.getKey();
            KeyCounter expectedOutputs = new KeyCounter();
            KeyCounter expectedContainerItems = new KeyCounter();
            KeyCounter[] craftingContainer = null;
            long extractedOperations = 0L;
            for (ICraftingProvider provider : craftingService.getProviders(details)) {
                if (provider.isBusy()) continue;
                PatternInterfaceCraftingMachine batchMachine = mmcr$batchMachine(provider, details);
                long batchSize = batchMachine == null ? 1L : Math.min(progress.mmcr$value(), batchMachine.maxBatchSize(details));
                if (batchSize < 1L) continue;

                if (craftingContainer == null) {
                    craftingContainer = mmcr$extractInputs(details, batchSize, level, expectedOutputs, expectedContainerItems);
                    if (craftingContainer == null) break;
                    extractedOperations = batchSize;
                }
                var patternPower = CraftingCpuHelper.calculatePatternPower(craftingContainer);
                if (energyService.extractAEPower(patternPower, Actionable.SIMULATE, PowerMultiplier.CONFIG)
                        < patternPower - 0.01) break;

                boolean pushed = batchMachine != null && extractedOperations > 1L
                        ? batchMachine.pushBatchPattern(details, craftingContainer, extractedOperations, null)
                        : provider.pushPattern(details, craftingContainer);
                if (!pushed) continue;

                energyService.extractAEPower(patternPower, Actionable.MODULATE, PowerMultiplier.CONFIG);
                pushedPatterns++;
                for (var output : expectedOutputs) {
                    jobAccess.mmcr$waitingFor().insert(output.getKey(), output.getLongValue(), Actionable.MODULATE);
                }
                for (var containerItem : expectedContainerItems) {
                    jobAccess.mmcr$waitingFor().insert(containerItem.getKey(), containerItem.getLongValue(), Actionable.MODULATE);
                    ((ElapsedTimeTrackerAccessor) jobAccess.mmcr$timeTracker())
                            .mmcr$addMaxItems(containerItem.getLongValue(), containerItem.getKey().getType());
                }
                cluster.markDirty();
                progress.mmcr$setValue(progress.mmcr$value() - extractedOperations);
                if (progress.mmcr$value() <= 0L) {
                    iterator.remove();
                    continue taskLoop;
                }
                if (pushedPatterns == maxPatterns) break taskLoop;
                expectedOutputs.reset();
                expectedContainerItems.reset();
                craftingContainer = null;
            }
            if (craftingContainer != null) CraftingCpuHelper.reinjectPatternInputs(inventory, craftingContainer);
        }
        return pushedPatterns;
    }

    private PatternInterfaceCraftingMachine mmcr$batchMachine(ICraftingProvider provider,
                                                              appeng.api.crafting.IPatternDetails details) {
        if (!(details instanceof AEProcessingPattern) || !(provider instanceof PatternProviderLogic logic)) {
            return null;
        }
        PatternInterfaceHost host = ((PatternProviderLogicBatchAccess) logic).mmcr$patternInterfaceHost();
        if (host == null) return null;
        ICraftingMachine machine = host.craftingMachine();
        return machine instanceof PatternInterfaceCraftingMachine batchMachine ? batchMachine : null;
    }

    private KeyCounter[] mmcr$extractInputs(appeng.api.crafting.IPatternDetails details, long operations, Level level,
                                            KeyCounter expectedOutputs, KeyCounter expectedContainerItems) {
        KeyCounter[] combined = null;
        for (long operation = 0; operation < operations; operation++) {
            KeyCounter[] extracted = CraftingCpuHelper.extractPatternInputs(details, inventory, level,
                    expectedOutputs, expectedContainerItems);
            if (extracted == null) {
                if (combined != null) CraftingCpuHelper.reinjectPatternInputs(inventory, combined);
                return null;
            }
            if (combined == null) {
                combined = extracted;
            } else {
                for (int index = 0; index < combined.length; index++) combined[index].addAll(extracted[index]);
            }
        }
        return combined;
    }
}
