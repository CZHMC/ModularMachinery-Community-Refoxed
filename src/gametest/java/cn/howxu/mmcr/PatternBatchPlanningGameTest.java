package cn.howxu.mmcr;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.GridHelper;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingCalculation;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.me.service.CraftingService;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.MachineControllerSpec;
import cn.howxu.mmcr.api.machine.PortRequirementSpec;
import cn.howxu.mmcr.api.recipe.MachineIngredient;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.helper.ProcessingComponent;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.compat.ae2lt.LightningTier;
import cn.howxu.mmcr.compat.ae2lt.LightningOutput;
import cn.howxu.mmcr.compat.ae2lt.LightningRequirement;
import cn.howxu.mmcr.compat.ae2lt.loaded.LoadedLightningBridge;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternBatchPlanning;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternBatchPlanning.BatchLimit;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternBatchPlanning.Snapshot;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternBatchSupport.Safety;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.pattern.PatternBatchDetails;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.internal.item.PatternBatchData;
import cn.howxu.mmcr.internal.multiblock.ComponentClaimPolicy;
import cn.howxu.mmcr.internal.multiblock.SharedIoCoordinator;
import cn.howxu.mmcr.internal.multiblock.StructureClaimRegistry;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.internal.tile.ParallelControllerBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Native AE calculation and extraction exercise graph safety without ticking a machine.
 * @author howxu <dev@howxu.cn>
 */
public final class PatternBatchPlanningGameTest {
    private PatternBatchPlanningGameTest() {
    }

    public static void onlyTheTwoMmcrHostsPublishBatchIdentity(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get().defaultBlockState());
        helper.setBlock(new BlockPos(2, 0, 0), ModBlocks.BLOCKS.get("eae_me_extended_pattern_interface").get().defaultBlockState());
        helper.setBlock(new BlockPos(4, 0, 0), AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
        var ordinary = helper.getBlockEntity(BlockPos.ZERO, PatternInterfaceBlockEntity.class);
        var extended = helper.getBlockEntity(new BlockPos(2, 0, 0), PatternInterfaceBlockEntity.class);
        var vanilla = helper.getBlockEntity(new BlockPos(4, 0, 0), PatternProviderBlockEntity.class);
        // Placement already queued each host's first-tick initialization. Never create its node in the fixture.
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                        ordinary.getMainNode().getNode() != null && extended.getMainNode().getNode() != null
                                && vanilla.getMainNode().getNode() != null,
                        "All three hosts initialize through their native first-tick lifecycle"))
                .thenExecute(() -> verifyPublishedBatchIdentity(helper, ordinary, extended, vanilla))
                .thenSucceed();
    }

    private static void verifyPublishedBatchIdentity(GameTestHelper helper, PatternInterfaceBlockEntity ordinary,
                                                     PatternInterfaceBlockEntity extended,
                                                     PatternProviderBlockEntity vanilla) {
        var recipeId = MMCR.id("batch_planning_publication");
        if (RecipeRegistry.getRecipe(recipeId) == null) {
            RecipeRegistry.registerStatic(MachineRecipe.fromCanonical(recipeId, MMCR.id("test_cube"), 20,
                    List.of(MachineRequirement.fromInput(new MachineIngredient.ItemIngredient(Ingredient.of(Items.PAPER), 1)),
                            MachineRequirement.itemOutput(new ItemStack(Items.PRISMARINE_SHARD))),
                    List.of(new MachineOutput.ItemOutput(new ItemStack(Items.PRISMARINE_SHARD), 1F)),
                    List.of(), 0, 1, false, true, false, Set.of()));
        }
        bindController(helper, ordinary, new BlockPos(0, 0, 2), 64);
        var extendedController = bindController(helper, extended, new BlockPos(2, 0, 2), 256);
        var ordinaryNode = ordinary.getMainNode().getNode();
        var extendedNode = extended.getMainNode().getNode();
        var hostConnection = GridHelper.createConnection(ordinaryNode, extendedNode);
        GridHelper.createConnection(ordinary.getMainNode().getNode(), vanilla.getMainNode().getNode());
        var physical = PatternDetailsHelper.encodeProcessingPattern(List.of(stack(Items.PAPER, 1)),
                List.of(stack(Items.PRISMARINE_SHARD, 1)));
        ordinary.getLogic().getPatternInv().setItemDirect(0, physical.copy());
        extended.getLogic().getPatternInv().setItemDirect(35, physical.copy());
        vanilla.getLogic().getPatternInv().setItemDirect(0, physical.copy());
        ordinary.getLogic().updatePatterns();
        extended.getLogic().updatePatterns();
        vanilla.getLogic().updatePatterns();
        vanilla.getLogic().setPriority(10_000);
        var bare = PatternDetailsHelper.decodePattern(physical, helper.getLevel());
        var unit = ordinary.getLogic().getAvailablePatterns().stream()
                .filter(pattern -> pattern instanceof PatternBatchDetails batch && batch.data().recipeId().equals(recipeId))
                .map(pattern -> (PatternBatchDetails) pattern).findFirst().orElseThrow();
        helper.assertTrue(extended.getLogic().getAvailablePatterns().contains(unit)
                        && vanilla.getLogic().getAvailablePatterns().equals(List.of(bare))
                        && PatternBatchPlanning.isBatchHost(ordinary) && PatternBatchPlanning.isBatchHost(extended)
                        && !PatternBatchPlanning.isBatchHost(vanilla),
                "Only the two explicit MMCR host kinds publish a separate bound identity, including extended slot 35");
        helper.assertTrue(AEItemKey.of(ordinary.getLogic().getPatternInv().getStackInSlot(0)).equals(bare.getDefinition())
                        && AEItemKey.of(extended.getLogic().getPatternInv().getStackInSlot(35)).equals(bare.getDefinition()),
                "Unit publication leaves both physical pattern slots unchanged");
        var grid = ordinary.getMainNode().getGrid();
        var captured = PatternBatchPlanning.capture(grid);
        helper.assertTrue(captured.limits().get(unit.getDefinition()).maxMultiplier() == 256,
                "Two heterogeneous hosts advertise their maximum single-lane capacity, never their sum");
        var service = (CraftingService) grid.getCraftingService();
        var batchProviders = new ArrayList<ICraftingProvider>();
        service.getProviders(unit.scaled(256)).forEach(batchProviders::add);
        helper.assertTrue(batchProviders.size() == 2 && batchProviders.contains(ordinary.getLogic())
                        && batchProviders.contains(extended.getLogic()) && !batchProviders.contains(vanilla.getLogic()),
                "Actual CraftingService lookup never routes a scaled task to a high-priority native bare provider");
        helper.assertTrue(PatternBatchPlanning.contains(ordinary, List.of(bare), unit.scaled(256))
                        && !PatternBatchPlanning.contains(ordinary, List.of(bare), bare),
                "The current binding permits scaled containment without adding an old-bare-job compatibility route");
        extendedController.setMachine(parallelMachine(64));
        extendedController.setFormed(true);
        extended.refreshBatchPatterns();
        helper.assertTrue(PatternBatchPlanning.capture(grid).limits().get(unit.getDefinition()).maxMultiplier() == 64
                        && captured.limits().get(unit.getDefinition()).maxMultiplier() == 256,
                "A capability refresh affects future calculations, never an already captured batch limit");
        // ManagedGridNode.create consumes its initialization data permanently; migrate live nodes by topology.
        hostConnection.destroy();
        ordinary.refreshBatchPatterns();
        extended.refreshBatchPatterns();
        var remainingGrid = ordinary.getMainNode().getGrid();
        var movedGrid = extended.getMainNode().getGrid();
        helper.assertTrue(movedGrid != remainingGrid
                        && ordinary.getMainNode().getNode() == ordinaryNode
                        && extended.getMainNode().getNode() == extendedNode
                        && PatternBatchPlanning.capture(movedGrid).limits().get(unit.getDefinition()).maxMultiplier() == 64
                        && PatternBatchPlanning.capture(remainingGrid).limits().get(unit.getDefinition()).maxMultiplier() == 64,
                "A topology split migrates the existing nodes and leaves each grid only its own provider capacity");
        extended.onChunkUnloaded();
        helper.assertTrue(PatternBatchPlanning.capture(movedGrid).limits().isEmpty()
                        && PatternBatchPlanning.capture(remainingGrid).limits().get(unit.getDefinition()).maxMultiplier() == 64,
                "Chunk unload removes the migrated provider without affecting the other grid's provider");
        ordinary.setRemoved();
        helper.assertTrue(PatternBatchPlanning.capture(remainingGrid).limits().isEmpty()
                        && captured.limits().get(unit.getDefinition()).maxMultiplier() == 256,
                "Unloading all hosts removes their grid profile without mutating an in-flight snapshot");
    }

    public static void fullRemainderAndSingleHugeBatchPreserveTotals(GameTestHelper helper) {
        var unit = unit(helper, "totals", List.of(stack(Items.IRON_INGOT, 3),
                        new GenericStack(AEFluidKey.of(Fluids.WATER), 1000)),
                List.of(stack(Items.GOLD_INGOT, 2), new GenericStack(AEFluidKey.of(Fluids.LAVA), 500)));
        var another = unit(helper, "other_binding", List.of(stack(Items.IRON_INGOT, 3),
                        new GenericStack(AEFluidKey.of(Fluids.WATER), 1000)),
                List.of(stack(Items.GOLD_INGOT, 2), new GenericStack(AEFluidKey.of(Fluids.LAVA), 500)));
        var original = new LinkedHashMap<IPatternDetails, Long>();
        original.put(unit, 10_000L);
        original.put(another, 10_000L);
        var rewritten = PatternBatchPlanning.rewrite(original, snapshot(256, unit, another));
        helper.assertTrue(rewritten.equals(Map.of(unit.scaled(256), 39L, unit.scaled(16), 1L,
                        another.scaled(256), 39L, another.scaled(16), 1L)),
                "Full and remainder tasks retain separate bindings and never overwrite each other");
        conserve(helper, original, rewritten);
        helper.assertTrue(original.get(unit) == 10_000L && original.get(another) == 10_000L,
                "Rewriting never changes the source AE crafts map");
        var shortOrder = Map.<IPatternDetails, Long>of(unit, 16L);
        var shortPlan = PatternBatchPlanning.rewrite(shortOrder, snapshot(256, unit));
        helper.assertTrue(shortPlan.equals(Map.of(unit.scaled(16), 1L)), "A short order is one exact remainder");
        conserve(helper, shortOrder, shortPlan);
        var hugeOrder = Map.<IPatternDetails, Long>of(unit, 2_147_483_647L);
        var hugePlan = PatternBatchPlanning.rewrite(hugeOrder, snapshot(2_147_483_647L, unit));
        helper.assertTrue(hugePlan.equals(Map.of(unit.scaled(2_147_483_647L), 1L)),
                "The supported huge order stays one batch rather than expanding per craft");
        conserve(helper, hugeOrder, hugePlan);
        helper.succeed();
    }

    public static void lightningBatchesPreserveNativePlanningAndAdmission(GameTestHelper helper) {
        helper.setBlock(BlockPos.ZERO, ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get().defaultBlockState());
        helper.setBlock(new BlockPos(2, 0, 0), ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get().defaultBlockState());
        var highHost = helper.getBlockEntity(BlockPos.ZERO, PatternInterfaceBlockEntity.class);
        var extremeHost = helper.getBlockEntity(new BlockPos(2, 0, 0), PatternInterfaceBlockEntity.class);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                        highHost.getMainNode().getNode() != null && extremeHost.getMainNode().getNode() != null,
                        "Lightning providers initialize through their native lifecycle"))
                .thenExecute(() -> {
                    verifyLightningBatch(helper, highHost, new BlockPos(0, 0, 2), LightningTier.HIGH_VOLTAGE,
                            LightningTier.EXTREME_HIGH_VOLTAGE);
                    verifyLightningBatch(helper, extremeHost, new BlockPos(2, 0, 2), LightningTier.EXTREME_HIGH_VOLTAGE,
                            LightningTier.HIGH_VOLTAGE);
                }).thenSucceed();
    }

    private static void verifyLightningBatch(GameTestHelper helper, PatternInterfaceBlockEntity host,
                                             BlockPos controllerPos, LightningTier inputTier, LightningTier outputTier) {
        var recipeId = MMCR.id("batch_planning_lightning_" + inputTier.requirementId().getPath());
        var inputKey = LoadedLightningBridge.FAMILY.adapter().toKey(inputTier);
        var outputKey = LoadedLightningBridge.FAMILY.adapter().toKey(outputTier);
        List<MachineOutput> outputs = List.of(new MachineOutput.ItemOutput(new ItemStack(Items.GOLD_INGOT, 3), 1F),
                new LightningOutput(outputTier, 1_000_000_000L, 1F));
        var requirements = new ArrayList<MachineRequirement>(List.of(
                MachineRequirement.fromInput(new MachineIngredient.ItemIngredient(Ingredient.of(Items.IRON_INGOT), 2)),
                LightningRequirement.input(inputTier, Integer.MAX_VALUE)));
        outputs.forEach(output -> requirements.add(OutputRegistry.toRequirement(output, List.of())));
        var recipe = MachineRecipe.fromCanonical(recipeId, MMCR.id("test_cube"), 20, requirements, outputs,
                List.of(), 0, 1, false, true, false, Set.of());
        RecipeRegistry.registerStatic(recipe);
        var controller = bindController(helper, host, controllerPos, 256L);
        var components = new ArrayList<>(controller.componentRuntime().components());
        components.add(new ProcessingComponent(null, host, host.getBlockPos(),
                host.getBlockPos().subtract(controller.getBlockPos()), List.of()));
        controller.componentRuntime().replaceComponents(components);
        helper.assertTrue(StructureClaimRegistry.get(helper.getLevel()).claim(controller.getBlockPos(), List.of(
                        new StructureClaimRegistry.Claim(host.getBlockPos(), ComponentClaimPolicy.EXCLUSIVE))).accepted(),
                "The admission fixture owns a real controller resource domain");
        var physical = PatternDetailsHelper.encodeProcessingPattern(
                List.of(stack(Items.IRON_INGOT, 4L), new GenericStack(inputKey, 4_294_967_294L)),
                List.of(stack(Items.GOLD_INGOT, 6L), new GenericStack(outputKey, 2_000_000_000L)));
        host.getLogic().getPatternInv().setItemDirect(0, physical);
        host.getLogic().updatePatterns();
        var unit = host.getLogic().getAvailablePatterns().stream()
                .filter(pattern -> pattern instanceof PatternBatchDetails batch && batch.data().recipeId().equals(recipeId))
                .map(pattern -> (PatternBatchDetails) pattern).findFirst().orElseThrow();
        var limits = PatternBatchPlanning.capture(host.getMainNode().getGrid());
        var materials = new KeyCounter();
        materials.add(AEItemKey.of(Items.IRON_INGOT), 40_000L);
        materials.add(inputKey, 42_949_672_940_000L);
        var requested = stack(Items.GOLD_INGOT, 60_000L);
        var normal = simulate(helper, List.of(unit), materials, requested, Snapshot.EMPTY);
        var batched = simulate(helper, List.of(unit), materials, requested, limits);
        helper.assertFalse(normal.simulation() || batched.simulation(), "Both lightning plans have all materials");
        helper.assertTrue(batched.patternTimes().equals(Map.of(unit.scaled(128L), 78L, unit.scaled(16L), 1L)),
                "Lightning recipes compress by single-lane capacity, accounting for two recipe crafts per pattern");
        conserve(helper, normal.patternTimes(), batched.patternTimes());
        helper.assertTrue(counter(normal.usedItems()).equals(counter(batched.usedItems())) && normal.bytes() == batched.bytes(),
                "Compression preserves native lightning material and CPU accounting");
        settle(helper, batched);
        var batch = unit.scaled(128L);
        var restored = PatternDetailsHelper.decodePattern(batch.getDefinition(), helper.getLevel());
        helper.assertTrue(batch.equals(restored) && restored.getOutputs().equals(batch.getOutputs())
                        && restored.getOutputs().contains(new GenericStack(outputKey, 256_000_000_000L)),
                "A saved lightning batch restores the exact scaled protocol");
        var inventory = new ListCraftingInventory(key -> {});
        inventory.insert(AEItemKey.of(Items.IRON_INGOT), 512L, Actionable.MODULATE);
        inventory.insert(inputKey, 549_755_813_632L, Actionable.MODULATE);
        var holders = CraftingCpuHelper.extractPatternInputs(restored, inventory, helper.getLevel(), new KeyCounter(), new KeyCounter());
        helper.assertTrue(holders != null, "Native CPU extraction retains complete long-amount lightning inputs");
        SharedIoCoordinator.get(helper.getLevel()).markControllerEligible(controller, helper.getLevel().getGameTime());
        helper.assertTrue(host.craftingMachine().pushPattern(restored, holders, null),
                "Real controller admission pays a complete long-amount lightning batch");
        helper.assertTrue(controller.runtimeSnapshot().crafting().parallelism() == 256L
                        && recipeId.equals(controller.runtimeSnapshot().crafting().recipeId())
                        && host.getLogic().getReturnInv().isEmpty(),
                "Both pattern crafts execute on one lane without truncation or spurious leftovers");
    }

    public static void reusedMoldAndCyclicDependencyRemainExecutable(GameTestHelper helper) {
        var mold = unit(helper, "mold", List.of(stack(Items.IRON_INGOT, 1), stack(Items.STICK, 1)),
                List.of(stack(Items.GOLD_INGOT, 1), stack(Items.STICK, 1)));
        var downstream = unit(helper, "mold_downstream", List.of(stack(Items.GOLD_INGOT, 1)),
                List.of(stack(Items.DIAMOND, 1)));
        var materials = new KeyCounter();
        materials.add(AEItemKey.of(Items.IRON_INGOT), 10);
        materials.add(AEItemKey.of(Items.STICK), 1);
        var limits = snapshot(256, mold, downstream);
        var plan = simulate(helper, List.of(mold, downstream), materials, stack(Items.DIAMOND, 10), limits);
        helper.assertFalse(plan.simulation(), "Native AE can plan a repeated mold using only one initial mold");
        helper.assertTrue(plan.patternTimes().equals(Map.of(mold, 10L, downstream, 10L)),
                "Mold self-loop poisons the entire connected downstream component");
        settle(helper, plan);

        var a = unit(helper, "cycle_a", List.of(stack(Items.COAL, 1)),
                List.of(stack(Items.REDSTONE, 10), stack(Items.CHARCOAL, 1)));
        var b = unit(helper, "cycle_b", List.of(stack(Items.CHARCOAL, 1)),
                List.of(stack(Items.LAPIS_LAZULI, 10), stack(Items.COAL, 1)));
        var related = unit(helper, "cycle_neighbor", List.of(stack(Items.REDSTONE, 1), stack(Items.LAPIS_LAZULI, 1)),
                List.of(stack(Items.EMERALD, 1)));
        var seeds = new KeyCounter();
        seeds.add(AEItemKey.of(Items.COAL), 1);
        seeds.add(AEItemKey.of(Items.CHARCOAL), 1);
        var cycle = simulate(helper, List.of(a, b, related), seeds, stack(Items.EMERALD, 10), snapshot(256, a, b, related));
        helper.assertFalse(cycle.simulation(), "AE can plan the seeded byproduct cycle at unit scale");
        helper.assertTrue(cycle.patternTimes().equals(Map.of(a, 1L, b, 1L, related, 10L)),
                "A to B to A keeps the adjacent ordinary task at unit scale as well");
        settle(helper, cycle);
        var containerBase = unit(helper, "container", List.of(stack(Items.WATER_BUCKET, 1)),
                List.of(stack(Items.GOLD_INGOT, 1)));
        var container = new PatternBatchDetails(new RemainingPattern(containerBase.original()), containerBase.data());
        var buckets = new KeyCounter();
        buckets.add(AEItemKey.of(Items.WATER_BUCKET), 10);
        var containerPlan = simulate(helper, List.of(container, downstream), buckets, stack(Items.DIAMOND, 10),
                snapshot(256, container, downstream));
        helper.assertFalse(containerPlan.simulation(), "Native simulation retains custom remaining containers");
        helper.assertTrue(containerPlan.patternTimes().equals(Map.of(container, 10L, downstream, 10L)),
                "Remaining-container semantics do not enter a falsely safe batching component");
        settle(helper, containerPlan);
        helper.succeed();
    }

    public static void acyclicUpstreamAndByproductsKeepUsedItems(GameTestHelper helper) {
        var upstream = unit(helper, "dag_upstream", List.of(stack(Items.IRON_INGOT, 1)),
                List.of(stack(Items.GOLD_INGOT, 1), stack(Items.GOLD_NUGGET, 1)));
        var downstream = unit(helper, "dag_downstream", List.of(stack(Items.GOLD_INGOT, 1), stack(Items.GOLD_NUGGET, 1)),
                List.of(stack(Items.DIAMOND, 1), stack(Items.EMERALD, 1)));
        var materials = new KeyCounter();
        materials.add(AEItemKey.of(Items.IRON_INGOT), 10_000L);
        var patterns = List.<IPatternDetails>of(upstream, downstream);
        var output = stack(Items.DIAMOND, 10_000L);
        var normal = simulate(helper, patterns, materials, output, Snapshot.EMPTY);
        var batched = simulate(helper, patterns, materials, output, snapshot(256, upstream, downstream));
        helper.assertFalse(normal.simulation() || batched.simulation(), "Both native AE calculations have all inputs");
        helper.assertTrue(normal.bytes() == batched.bytes()
                        && counter(normal.usedItems()).equals(counter(batched.usedItems()))
                        && counter(normal.emittedItems()).equals(counter(batched.emittedItems()))
                        && normal.finalOutput().equals(batched.finalOutput()),
                "Compression preserves AE bytes, initial materials, emitted items and final output");
        conserve(helper, normal.patternTimes(), batched.patternTimes());
        helper.assertTrue(batched.patternTimes().size() == 4, "Both upstream and downstream DAG tasks are batched");
        settle(helper, batched);
        helper.succeed();
    }

    public static void unknownRequirementsPoisonOnlyEnumerableComponents(GameTestHelper helper) {
        var unknown = unit(helper, "unknown", List.of(stack(Items.IRON_INGOT, 1)), List.of(stack(Items.GOLD_INGOT, 1)));
        var adjacent = unit(helper, "unknown_neighbor", List.of(stack(Items.GOLD_INGOT, 1)), List.of(stack(Items.DIAMOND, 1)));
        var isolated = unit(helper, "isolated", List.of(stack(Items.COAL, 1)), List.of(stack(Items.EMERALD, 1)));
        Map<AEItemKey, BatchLimit> limits = new HashMap<>(snapshot(256, unknown, adjacent, isolated).limits());
        limits.put(unknown.getDefinition(), new BatchLimit(256, Safety.UNPROVEN));
        var tasks = Map.<IPatternDetails, Long>of(unknown, 1000L, adjacent, 1000L, isolated, 1000L);
        var rewritten = PatternBatchPlanning.rewrite(tasks, new Snapshot(limits));
        helper.assertTrue(rewritten.get(unknown) == 1000L && rewritten.get(adjacent) == 1000L
                        && rewritten.get(isolated.scaled(256)) == 3L && rewritten.get(isolated.scaled(232)) == 1L,
                "Unknown hidden requirements block the related component, not an independent proven DAG");
        conserve(helper, tasks, rewritten);
        var opaque = new OpaquePattern(unknown.original());
        var opaqueTasks = Map.<IPatternDetails, Long>of(opaque, 10L, isolated, 1000L);
        helper.assertTrue(PatternBatchPlanning.rewrite(opaqueTasks, new Snapshot(limits)).equals(opaqueTasks),
                "An opaque custom input protocol cannot supply a complete graph, so the whole plan stays unchanged");
        helper.succeed();
    }

    public static void planningDoesNotTouchLiveWorldOrInvokeHooks(GameTestHelper helper) {
        var unit = unit(helper, "concurrent", List.of(stack(Items.IRON_INGOT, 1)), List.of(stack(Items.GOLD_INGOT, 1)));
        var mutable = new HashMap<>(snapshot(256, unit).limits());
        var captured = new Snapshot(mutable);
        var tasks = Map.<IPatternDetails, Long>of(unit, 10_000L);
        mutable.put(unit.getDefinition(), new BatchLimit(64, Safety.UNPROVEN));
        var first = CompletableFuture.supplyAsync(() -> PatternBatchPlanning.rewrite(tasks, captured));
        var second = CompletableFuture.supplyAsync(() -> PatternBatchPlanning.rewrite(tasks, new Snapshot(mutable)));
        helper.assertTrue(first.join().equals(Map.of(unit.scaled(256), 39L, unit.scaled(16), 1L))
                        && second.join().equals(tasks),
                "Concurrent calculations read their own immutable capability/safety snapshot");
        helper.assertTrue(captured.limits().get(unit.getDefinition()).safety() == Safety.PROVEN_LINEAR,
                "Later host/profile invalidation cannot modify an already captured calculation");
        helper.succeed();
    }

    public static void mixedProvidersNeverDispatchBatchToVanilla(GameTestHelper helper) {
        var unit = unit(helper, "routing", List.of(stack(Items.IRON_INGOT, 1)), List.of(stack(Items.GOLD_INGOT, 1)));
        var otherBinding = unit(helper, "other_route", List.of(stack(Items.IRON_INGOT, 1)), List.of(stack(Items.GOLD_INGOT, 1)));
        helper.assertTrue(PatternBatchPlanning.providerKey(unit.scaled(256)).equals(unit)
                        && !PatternBatchPlanning.providerKey(unit.scaled(256)).equals(unit.original())
                        && !PatternBatchPlanning.providerKey(unit.scaled(256)).equals(otherBinding)
                        && PatternBatchPlanning.providerKey(unit.original()) == unit.original(),
                "Provider routing uses the exact MMCR unit binding and leaves vanilla lookup unchanged");
        helper.succeed();
    }

    private static MachineControllerBlockEntity bindController(GameTestHelper helper, PatternInterfaceBlockEntity host,
                                                               BlockPos position, long parallelism) {
        helper.setBlock(position, ModBlocks.controllerFor(MMCR.id("test_cube")).get().defaultBlockState());
        var controller = helper.getBlockEntity(position, MachineControllerBlockEntity.class);
        controller.setMachine(parallelMachine(parallelism));
        var parallelPos = position.above();
        helper.setBlock(parallelPos, ModBlocks.BLOCKS.get("parallel_controller_pro").get().defaultBlockState());
        var parallel = helper.getBlockEntity(parallelPos, ParallelControllerBlockEntity.class);
        parallel.setCurrentParallelism(Math.toIntExact(parallelism));
        controller.componentRuntime().replaceComponents(List.of(new ProcessingComponent(
                null, parallel, parallelPos, BlockPos.ZERO, List.of())));
        host.linkControllerAppearance(controller.getBlockPos(), null);
        controller.componentRuntime().replaceLinkedPortPositions(Set.of(host.getBlockPos()));
        controller.setFormed(true);
        return controller;
    }

    private static DynamicMachine parallelMachine(long parallelism) {
        var id = MMCR.id("test_cube");
        return new DynamicMachine(id, "runtime test", new BlockArray(Map.of()), MachineControllerSpec.defaultsFor(id),
                PortRequirementSpec.none(), List.of(), Map.of(), parallelism, true, false, 1);
    }

    private static GenericStack stack(Item item, long amount) {
        return new GenericStack(AEItemKey.of(item), amount);
    }

    private static PatternBatchDetails unit(GameTestHelper helper, String recipe, List<GenericStack> inputs,
                                             List<GenericStack> outputs) {
        var encoded = PatternDetailsHelper.encodeProcessingPattern(inputs, outputs);
        var original = PatternDetailsHelper.decodePattern(encoded, helper.getLevel());
        helper.assertTrue(original != null, "Native processing pattern decodes");
        return new PatternBatchDetails(original, new PatternBatchData(1, MMCR.id("batch_planning_test"), MMCR.id(recipe), 1, 1));
    }

    private static Snapshot snapshot(long maximum, PatternBatchDetails... units) {
        Map<AEItemKey, BatchLimit> limits = new HashMap<>();
        for (var unit : units) limits.put(unit.getDefinition(), new BatchLimit(maximum, Safety.PROVEN_LINEAR));
        return new Snapshot(limits);
    }

    private static void conserve(GameTestHelper helper, Map<IPatternDetails, Long> before, Map<IPatternDetails, Long> after) {
        helper.assertTrue(weighted(before, false).equals(weighted(after, false))
                        && weighted(before, true).equals(weighted(after, true)),
                "Every resource key's total weighted input and promised output is exactly conserved");
    }

    private static Map<AEKey, Long> weighted(Map<IPatternDetails, Long> tasks, boolean outputs) {
        Map<AEKey, Long> totals = new HashMap<>();
        tasks.forEach((pattern, count) -> {
            if (outputs) {
                for (var output : pattern.getOutputs()) {
                    totals.merge(output.what(), Math.multiplyExact(output.amount(), count), Math::addExact);
                }
            } else {
                for (var input : pattern.getInputs()) {
                    var template = input.getPossibleInputs()[0];
                    totals.merge(template.what(), Math.multiplyExact(Math.multiplyExact(template.amount(),
                            input.getMultiplier()), count), Math::addExact);
                }
            }
        });
        return totals;
    }

    private static Map<AEKey, Long> counter(KeyCounter counter) {
        Map<AEKey, Long> result = new HashMap<>();
        for (var entry : counter) result.put(entry.getKey(), entry.getLongValue());
        return result;
    }

    /** Runs the real AE dependency simulator synchronously, with only its time-slicing disabled in the fixture. */
    private static CraftingPlan simulate(GameTestHelper helper, List<? extends IPatternDetails> patterns,
                                         KeyCounter materials, GenericStack output, Snapshot snapshot) {
        var crafting = (ICraftingService) Proxy.newProxyInstance(ICraftingService.class.getClassLoader(),
                new Class<?>[]{ICraftingService.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCraftingFor" -> patterns.stream()
                            .filter(pattern -> pattern.getPrimaryOutput().what().equals(args[0]))
                            .toList();
                    case "canEmitFor" -> false;
                    case "getFuzzyCraftable" -> null;
                    default -> throw new AssertionError("Unexpected simulation crafting call: " + method);
                });
        var storage = (IStorageService) Proxy.newProxyInstance(IStorageService.class.getClassLoader(),
                new Class<?>[]{IStorageService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getCachedInventory")) return materials;
                    throw new AssertionError("Unexpected simulation storage call: " + method);
                });
        var grid = (IGrid) Proxy.newProxyInstance(IGrid.class.getClassLoader(), new Class<?>[]{IGrid.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getStorageService" -> storage;
                    case "getCraftingService" -> crafting;
                    default -> throw new AssertionError("Unexpected live grid call: " + method);
                });
        var node = (IGridNode) Proxy.newProxyInstance(IGridNode.class.getClassLoader(), new Class<?>[]{IGridNode.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getGrid")) return grid;
                    throw new AssertionError("Unexpected live node call: " + method);
                });
        var requester = new ICraftingSimulationRequester() {
            @Override
            public IActionSource getActionSource() {
                return null;
            }

            @Override
            public IGridNode getGridNode() {
                return node;
            }
        };
        var calculation = new TestCalculation(helper.getLevel(), grid, requester, output, snapshot);
        try {
            var increment = CraftingCalculation.class.getDeclaredField("incTime");
            increment.setAccessible(true);
            increment.setInt(calculation, Integer.MIN_VALUE);
            var compute = CraftingCalculation.class.getDeclaredMethod("computePlan");
            compute.setAccessible(true);
            return (CraftingPlan) compute.invoke(calculation);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Native AE calculation failed", exception);
        }
    }

    /** Uses native CPU extraction/rollback to check scheduling, not a second input-matching implementation. */
    private static void settle(GameTestHelper helper, CraftingPlan plan) {
        var inventory = new ListCraftingInventory(key -> {});
        for (var entry : plan.usedItems()) inventory.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
        var remaining = new LinkedHashMap<>(plan.patternTimes());
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (var pattern : new ArrayList<>(remaining.keySet())) {
                var outputs = new KeyCounter();
                var containers = new KeyCounter();
                if (CraftingCpuHelper.extractPatternInputs(pattern, inventory, helper.getLevel(), outputs, containers) == null) continue;
                for (var entry : outputs) inventory.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                for (var entry : containers) inventory.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                long count = remaining.get(pattern) - 1;
                if (count == 0) remaining.remove(pattern);
                else remaining.put(pattern, count);
                progress = true;
            }
            helper.assertTrue(progress, "All remaining tasks can execute with only the plan's original usedItems");
        }
        helper.assertTrue(inventory.extract(plan.finalOutput().what(), plan.finalOutput().amount(), Actionable.SIMULATE)
                        == plan.finalOutput().amount(), "The complete simulated order settles exactly");
    }

    /** Supplies a fixed data-only snapshot while retaining the real AE calculation and plan builder.
     * @author howxu <dev@howxu.cn>
     */
    private static final class TestCalculation extends CraftingCalculation implements PatternBatchPlanning.SnapshotSource {
        private final Snapshot snapshot;

        private TestCalculation(Level level, IGrid grid, ICraftingSimulationRequester requester,
                                GenericStack output, Snapshot snapshot) {
            super(level, grid, requester, output, CalculationStrategy.REPORT_MISSING_ITEMS);
            this.snapshot = snapshot;
        }

        @Override
        public Snapshot mmcr$batchSnapshot() {
            return snapshot;
        }
    }

    /** An untrusted custom protocol must never be treated as a native processing whitelist entry.
     * @author howxu <dev@howxu.cn>
     */
    private record OpaquePattern(IPatternDetails delegate) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return delegate.getDefinition(); }
        @Override public IInput[] getInputs() { return delegate.getInputs(); }
        @Override public List<GenericStack> getOutputs() { return delegate.getOutputs(); }
    }

    /** Custom container protocol, intentionally outside the exact native processing whitelist.
     * @author howxu <dev@howxu.cn>
     */
    private record RemainingPattern(IPatternDetails delegate) implements IPatternDetails {
        @Override public AEItemKey getDefinition() { return delegate.getDefinition(); }
        @Override public List<GenericStack> getOutputs() { return delegate.getOutputs(); }

        @Override
        public IInput[] getInputs() {
            var original = delegate.getInputs()[0];
            return new IInput[]{new IInput() {
                @Override public GenericStack[] getPossibleInputs() { return original.getPossibleInputs(); }
                @Override public long getMultiplier() { return original.getMultiplier(); }
                @Override public boolean isValid(AEKey key, Level level) { return original.isValid(key, level); }
                @Override public AEKey getRemainingKey(AEKey key) { return AEItemKey.of(Items.BUCKET); }
            }};
        }
    }
}
