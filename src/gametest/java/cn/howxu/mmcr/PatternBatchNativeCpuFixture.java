package cn.howxu.mmcr;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageProvider;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.MachineControllerSpec;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.machine.PortRequirementSpec;
import cn.howxu.mmcr.api.recipe.MachineIngredient;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.ParallelTier;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.pattern.PatternBatchDetails;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.internal.block.MachineControllerBlock;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.internal.tile.EnergyHatchBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/** Native CPU fixture: only hardware capacity, mounted storage and observations are test-owned.
 * @author howxu <dev@howxu.cn>
 */
final class PatternBatchNativeCpuFixture {
    static final BlockPos PORT = new BlockPos(0, 1, 0);
    static final BlockPos ENERGY = new BlockPos(4, 0, 0);
    static final BlockPos CPU = new BlockPos(4, 0, 3);
    static final BlockPos FE_PORT = new BlockPos(0, 0, 2);
    static final long INT_MAX = 2_147_483_647L;
    static final long DOUBLE_INT_MAX = 4_294_967_294L;

    final GameTestHelper helper;
    final Identifier recipeId;
    final AEKey input;
    final AEKey output;
    final long total;
    final DynamicMachine machine;
    final BlockPos cpuPosition;
    final long feBudget;
    PatternInterfaceBlockEntity host;
    MachineControllerBlockEntity controller;
    CreativeEnergyCellBlockEntity energy;
    LongStorage storage = new LongStorage();
    final CountingProvider provider;
    final EnergyMeter power = new EnergyMeter();
    IGridConnection hostConnection;
    long expectedInputBalance;
    Future<ICraftingPlan> calculation;
    ICraftingPlan plan;

    PatternBatchNativeCpuFixture(GameTestHelper helper, String name, boolean fluid, int unitAmount,
                                 long crafts, ParallelTier tier) {
        this(helper, name, fluid, unitAmount, crafts, tier, CPU, false);
    }

    PatternBatchNativeCpuFixture(GameTestHelper helper, String name, boolean fluid, int unitAmount,
                                 long crafts, ParallelTier tier, BlockPos cpuPosition, boolean machineEnergy) {
        this.helper = helper;
        this.cpuPosition = cpuPosition;
        // Ordinary FE admission only checks supply; forty actual recipe ticks pay.
        feBudget = machineEnergy ? Math.multiplyExact(crafts, 40L) : 0L;
        if (!cpuPosition.equals(CPU)) {
            var bounds = helper.testInfo.getStructureBounds();
            BlockPos end = helper.absolutePos(cpuPosition.offset(1, 0, 0));
            helper.assertTrue(bounds.contains(end.getX() + 0.5, end.getY() + 0.5, end.getZ() + 0.5),
                    "Cross-chunk fixture requires its own reserved 24x4x8 structure, never a neighbor's space");
            helper.assertTrue(!ChunkPos.containing(helper.absolutePos(BlockPos.ZERO)).equals(ChunkPos.containing(end)),
                    "Actual machine and CPU coordinates belong to different chunks");
        }
        total = Math.multiplyExact(crafts, unitAmount);
        input = fluid ? AEFluidKey.of(Fluids.WATER) : AEItemKey.of(Items.IRON_INGOT);
        output = fluid ? AEFluidKey.of(Fluids.LAVA) : AEItemKey.of(Items.GOLD_INGOT);
        Identifier machineId = MMCR.id("pattern_batch_cpu_" + name);
        recipeId = MMCR.id("pattern_batch_cpu_" + name + "_recipe");
        helper.setBlock(BlockPos.ZERO, ModBlocks.controllerFor(MMCR.id("test_cube")).get().defaultBlockState()
                .setValue(MachineControllerBlock.FACING, Direction.SOUTH));
        helper.setBlock(PORT, ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get().defaultBlockState());
        helper.setBlock(new BlockPos(1, 0, 0), ModBlocks.BLOCKS.get("factory_controller").get().defaultBlockState());
        helper.setBlock(new BlockPos(2, 0, 0), ModBlocks.BLOCKS.get("factory_controller").get().defaultBlockState());
        helper.setBlock(new BlockPos(0, 0, 1), ModBlocks.BLOCKS.get(tier.idSuffix()).get().defaultBlockState());
        helper.setBlock(ENERGY, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        helper.setBlock(cpuPosition, AEBlocks.CRAFTING_STORAGE_64K.block().defaultBlockState());
        // CraftingService indexes owners by their exact class. This ordinary unit makes the
        // native service discover the cluster containing our capacity-only subclass too.
        helper.setBlock(cpuPosition.offset(1, 0, 0), AEBlocks.CRAFTING_UNIT.block().defaultBlockState());
        var originalCpu = helper.getBlockEntity(cpuPosition, CraftingBlockEntity.class);
        var largeCpu = new LargeStorageCpu(originalCpu.getType(), originalCpu.getBlockPos(), originalCpu.getBlockState());
        helper.getLevel().removeBlockEntity(originalCpu.getBlockPos());
        helper.getLevel().setBlockEntity(largeCpu);

        Map<BlockPos, BlockPredicate> pattern = new LinkedHashMap<>(Map.of(
                PORT, new BlockPredicate.OfBlock(ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get()),
                new BlockPos(1, 0, 0), new BlockPredicate.OfBlock(ModBlocks.BLOCKS.get("factory_controller").get()),
                new BlockPos(2, 0, 0), new BlockPredicate.OfBlock(ModBlocks.BLOCKS.get("factory_controller").get()),
                new BlockPos(0, 0, 1), new BlockPredicate.OfBlock(ModBlocks.BLOCKS.get(tier.idSuffix()).get())));
        if (machineEnergy) {
            var block = ModBlocks.BLOCKS.get("extended_energy_input_hatch_ultimate").get();
            helper.setBlock(FE_PORT, block.defaultBlockState());
            pattern.put(FE_PORT, new BlockPredicate.OfBlock(block));
            energyHatch().energyStorage().setAmount(feBudget);
        }
        machine = new DynamicMachine(machineId, "block.mmcr.test_cube", new BlockArray(pattern),
                MachineControllerSpec.defaultsFor(machineId), PortRequirementSpec.none(), List.of(), Map.of(),
                tier.maxParallelism(), true, true, 2);
        if (!MachineRegistry.containsStatic(machineId)) MachineRegistry.register(machine);
        if (!RecipeRegistry.containsStatic(recipeId)) {
            var ingredient = fluid
                    ? new MachineIngredient.FluidIngredient(FluidIngredient.of(Fluids.WATER), unitAmount)
                    : new MachineIngredient.ItemIngredient(Ingredient.of(Items.IRON_INGOT), unitAmount);
            MachineRequirement product = fluid
                    ? MachineRequirement.fluidOutput(new FluidStack(Fluids.LAVA, unitAmount))
                    : MachineRequirement.itemOutput(new ItemStack(Items.GOLD_INGOT, unitAmount));
            List<MachineRequirement> requirements = new ArrayList<>(List.of(MachineRequirement.fromInput(ingredient), product));
            if (machineEnergy) requirements.add(MachineRequirement.fromInput(new MachineIngredient.EnergyIngredient(1L)));
            RecipeRegistry.registerStatic(MachineRecipe.fromCanonical(recipeId, machineId, 40,
                    requirements, List.of(), List.of(),
                    0, 2, false, true, false, Set.of()));
        }
        controller = helper.getBlockEntity(BlockPos.ZERO, MachineControllerBlockEntity.class);
        controller.setMachine(machine);
        controller.setStructureCheckIntervalForTesting(1);
        host = helper.getBlockEntity(PORT, PatternInterfaceBlockEntity.class);
        provider = new CountingProvider(host.getLogic());
        // Install before ready-time node creation, retaining the real MMCR host and logic.
        host.getMainNode().addService(ICraftingProvider.class, provider);
        energy = helper.getBlockEntity(ENERGY, CreativeEnergyCellBlockEntity.class);
        energy.getMainNode().addService(IStorageProvider.class, mounts -> mounts.mount(storage, 0));
        // Two same-key slots prove aggregation without building an enormous ItemStack.
        storage.setStack(0, new GenericStack(input, total / 2));
        storage.setStack(1, new GenericStack(input, total - total / 2));
        host.getLogic().getPatternInv().setItemDirect(0, PatternDetailsHelper.encodeProcessingPattern(
                unitAmount == 2 ? List.of(new GenericStack(input, 1), new GenericStack(input, 1))
                        : List.of(new GenericStack(input, 1)),
                List.of(new GenericStack(output, unitAmount))));
    }

    GameTestSequence calculate() {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                host.getMainNode().getNode() != null && energy.getMainNode().getNode() != null
                        && cpuBlock().getMainNode().getNode() != null, "Native AE2 nodes initialize"))
                .thenExecute(() -> {
                    connectNetwork(true);
                    controller.requestImmediateStructureCheck();
                }).thenWaitUntil(() -> helper.assertTrue(controller.structureSnapshot().formed()
                                && controller.factorySchedulerThreadCount() >= 2
                                && host.getMainNode().isActive() && cluster() != null && cluster().isActive(),
                        "The actual factory, two lanes and native crafting CPU become available"))
                .thenExecute(() -> host.getLogic().updatePatterns())
                .thenWaitUntil(() -> helper.assertTrue(grid().getCraftingService().isCraftable(output),
                        "The grid discovers the real interface pattern"))
                .thenExecute(() -> calculation = grid().getCraftingService().beginCraftingCalculation(
                        helper.getLevel(), new ICraftingSimulationRequester() {
                            @Override public IActionSource getActionSource() { return IActionSource.ofMachine(host); }
                            @Override public IGridNode getGridNode() { return host.getMainNode().getNode(); }
                        }, output, total, CalculationStrategy.REPORT_MISSING_ITEMS))
                .thenWaitUntil(() -> helper.assertTrue(calculation.isDone(), "Asynchronous native plan completes"))
                .thenExecute(() -> {
                    try {
                        plan = calculation.get(); // isDone was observed; never blocks the server for planning.
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted obtaining completed crafting plan", exception);
                    } catch (ExecutionException exception) {
                        throw new AssertionError("Native crafting calculation failed", exception.getCause());
                    }
                    helper.assertTrue(!plan.simulation() && plan.missingItems().isEmpty()
                                    && plan.usedItems().get(input) == total && plan.emittedItems().isEmpty(),
                            "The actual plan uses all and only the stocked material without emitted substitutes");
                    helper.assertTrue(storage.operations < 4096,
                            "Planning performs bounded storage operations, not one operation per craft");
                }).thenWaitUntil(() -> {
                    double needed = (double) total / input.getAmountPerOperation();
                    helper.assertTrue(grid().getEnergyService().extractAEPower(needed, Actionable.SIMULATE,
                                    PowerMultiplier.CONFIG) >= needed,
                            "The real grid can supply the complete native pattern energy before dispatch");
                });
    }

    void submit() {
        helper.assertTrue(grid().getCraftingService().submitJob(plan, null, cluster(), false,
                        IActionSource.ofMachine(host)).successful(), "Native CPU accepts the actual calculated plan");
        cpu().setJobSuspended(true);
        helper.assertTrue(cpu().getStored(input) == total && stored(input) == 0
                        && cpu().getWaitingFor(output) == 0 && cpu().getPendingOutputs(output) == total,
                "The CPU, not the interface, owns every un-dispatched unit");
        power.delegate = grid().getEnergyService();
    }

    int dispatch() {
        return cpu().executeCrafting(1, (CraftingService) grid().getCraftingService(), power.service, helper.getLevel());
    }

    void assertOneActiveBatch(long parallelism) {
        var snapshot = controller.runtimeSnapshot().factory();
        var active = snapshot.lanes().stream().filter(lane -> recipeId.equals(lane.recipeId())).toList();
        helper.assertTrue(snapshot.activeLaneCount() == 1 && active.size() == 1
                        && active.getFirst().parallelism() == parallelism,
                "One standard push starts exactly one actual factory lane at exact long parallelism");
    }

    void assertSettled(long pushes) {
        helper.assertTrue(!cpu().hasJob() && cpu().getWaitingFor(output) == 0
                        && cpu().getStored(input) == 0 && stored(input) == expectedInputBalance && stored(output) == total
                        && host.getLogic().getReturnInv().isEmpty()
                        && controller.runtimeSnapshot().factory().activeLaneCount() == 0,
                "Actual recipe output drains through the native return inventory and settles the CPU exactly; job="
                        + cpu().hasJob() + ", waiting=" + cpu().getWaitingFor(output) + ", input=" + stored(input)
                        + ", output=" + stored(output) + ", returns=" + returnAmount(output)
                        + ", factory=" + controller.runtimeSnapshot().factory());
        helper.assertTrue(provider.accepted == pushes && provider.attempts < 4096 && storage.operations < 16384,
                "Completed production uses bounded operations and no duplicate accepted pushes");
    }

    long stored(AEKey key) { return storage.getAvailableStacks().get(key); }
    IGrid grid() { return host.getMainNode().getNode().getGrid(); }
    CraftingBlockEntity cpuBlock() { return helper.getBlockEntity(cpuPosition, CraftingBlockEntity.class); }
    CraftingCPUCluster cluster() { return cpuBlock().getCluster(); }
    CraftingCpuLogic cpu() { return cluster().craftingLogic; }

    EnergyHatchBlockEntity energyHatch() { return helper.getBlockEntity(FE_PORT, EnergyHatchBlockEntity.class); }

    void connectNetwork(boolean connectHost) {
        GridHelper.createConnection(energy.getMainNode().getNode(), cpuBlock().getMainNode().getNode());
        if (connectHost) reconnectHost();
    }

    void reconnectHost() {
        hostConnection = GridHelper.createConnection(host.getMainNode().getNode(), energy.getMainNode().getNode());
    }

    void disconnectHost() { hostConnection.destroy(); }

    void addSpareMaterial() {
        expectedInputBalance = total;
        helper.assertTrue(grid().getStorageService().getInventory().insert(input, total, Actionable.MODULATE,
                        IActionSource.ofMachine(host)) == total,
                "A second complete batch is genuinely available in the network, but has no CPU order");
    }

    long returnAmount(AEKey key) { return host.getLogic().getReturnInv().getAvailableStacks().get(key); }

    void fillReturnSlots(AEKey blocker) {
        storage.blocked.add(blocker);
        var returns = host.getLogic().getReturnInv();
        for (int slot = 0; slot < returns.size(); slot++) returns.setStack(slot, new GenericStack(blocker, 1));
        host.saveChanges();
    }

    void clearReturnBlocker(AEKey blocker) {
        var returns = host.getLogic().getReturnInv();
        for (int slot = 0; slot < returns.size(); slot++) {
            if (blocker.equals(returns.getKey(slot))) returns.setStack(slot, null);
        }
        storage.blocked.remove(blocker);
        host.saveChanges();
    }

    /** Observes actual runtime serialization, never source text or fabricated execution state. */
    List<ValueInput> savedLanes() {
        ValueInput root = TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(),
                controller.saveCustomOnly(helper.getLevel().registryAccess()));
        ValueInput factory = root.childOrEmpty("factory_runtime");
        List<ValueInput> result = new ArrayList<>();
        for (int lane = 0; lane < factory.getIntOr("lane_count", 0); lane++) {
            result.add(factory.childOrEmpty("lane_" + lane).childOrEmpty("runtime"));
        }
        return result;
    }

    /** Save the real CPU core, controller execution/ledger, interface balances and FE owner together.
     * Recreates only this fixture's BEs at identical positions; never unloads a shared world chunk.
     */
    void rebuildPaidOwners() {
        List<BlockEntity> owners = List.of(energyHatch(), host,
                helper.getBlockEntity(new BlockPos(1, 0, 0), BlockEntity.class),
                helper.getBlockEntity(new BlockPos(2, 0, 0), BlockEntity.class),
                helper.getBlockEntity(new BlockPos(0, 0, 1), BlockEntity.class), controller, cpuBlock(),
                helper.getBlockEntity(cpuPosition.offset(1, 0, 0), CraftingBlockEntity.class), energy);
        Map<BlockEntity, CompoundTag> saved = new LinkedHashMap<>();
        for (var owner : owners) saved.put(owner, owner.saveWithFullMetadata(helper.getLevel().registryAccess()));
        var balances = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        storage.writeToChildTag(balances, "balances");
        Set<AEKey> blocked = Set.copyOf(storage.blocked);
        long operations = storage.operations;
        controller.onChunkUnloaded(); // removal must follow unload semantics, not player destruction/cancellation.
        host.onChunkUnloaded();
        for (var owner : owners) helper.getLevel().removeBlockEntity(owner.getBlockPos());
        storage = new LongStorage();
        storage.readFromChildTag(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), balances.buildResult()), "balances");
        storage.blocked.addAll(blocked);
        storage.operations = operations;
        for (var old : owners) {
            BlockEntity restored = old instanceof LargeStorageCpu
                    ? new LargeStorageCpu(old.getType(), old.getBlockPos(), old.getBlockState())
                    : old.getType().create(old.getBlockPos(), old.getBlockState());
            helper.assertTrue(restored != null && restored != old, "Every persisted owner is a genuinely new block entity");
            restored.setLevel(helper.getLevel());
            if (restored instanceof MachineControllerBlockEntity replacement) {
                controller = replacement;
                replacement.setMachine(machine);
                replacement.setStructureCheckIntervalForTesting(1);
            }
            if (restored instanceof PatternInterfaceBlockEntity replacement) {
                host = replacement;
                provider.delegate = replacement.getLogic();
                replacement.getMainNode().addService(ICraftingProvider.class, provider);
            }
            if (restored instanceof CreativeEnergyCellBlockEntity replacement) {
                energy = replacement;
                replacement.getMainNode().addService(IStorageProvider.class, mounts -> mounts.mount(storage, 0));
            }
            restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess(), saved.get(old)));
            helper.getLevel().setBlockEntity(restored);
        }
        controller.requestImmediateStructureCheck();
    }

    /** Only capacity differs: all cluster formation, planning, execution and persistence remain AE2's.
     * This is not evidence that a stock 64K block can hold the original multi-gigabyte plan.
     * @author howxu <dev@howxu.cn>
     */
    private static final class LargeStorageCpu extends CraftingBlockEntity {
        LargeStorageCpu(BlockEntityType<?> type, BlockPos pos, BlockState state) { super(type, pos, state); }
        @Override public long getStorageBytes() { return 1L << 40; }
    }

    /** Fixed-slot, long-count external ME storage, never generates recipe outputs itself.
     * @author howxu <dev@howxu.cn>
     */
    static final class LongStorage extends GenericStackInv {
        long operations;
        final Set<AEKey> blocked = new HashSet<>();
        LongStorage() { super(null, 4); }
        @Override public long getMaxAmount(AEKey key) { return Long.MAX_VALUE; }
        @Override public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            operations++;
            if (blocked.contains(what)) return 0;
            long remaining = Long.MAX_VALUE - getAvailableStacks().get(what);
            return super.insert(what, Math.min(amount, remaining), mode, source);
        }
        @Override public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            operations++;
            return super.extract(what, amount, mode, source);
        }
    }

    /** Transparent node-service proxy; counts standard CPU calls and delegates real acceptance.
     * @author howxu <dev@howxu.cn>
     */
    static final class CountingProvider implements ICraftingProvider {
        private ICraftingProvider delegate;
        long attempts;
        long accepted;
        final Map<Long, Long> acceptedMultipliers = new LinkedHashMap<>();
        KeyCounter lastInputs = new KeyCounter();
        CountingProvider(ICraftingProvider delegate) { this.delegate = delegate; }
        @Override public List<IPatternDetails> getAvailablePatterns() { return delegate.getAvailablePatterns(); }
        @Override public int getPatternPriority() { return delegate.getPatternPriority(); }
        @Override public Set<AEKey> getEmitableItems() { return delegate.getEmitableItems(); }
        @Override public boolean isBusy() { return delegate.isBusy(); }
        @Override public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) {
            attempts++;
            lastInputs = new KeyCounter();
            for (var counter : inputs) lastInputs.addAll(counter);
            boolean result = delegate.pushPattern(details, inputs);
            if (result) {
                accepted++;
                if (details instanceof PatternBatchDetails batch) {
                    acceptedMultipliers.merge(batch.data().multiplier(), 1L, Long::sum);
                }
            }
            return result;
        }
    }

    /** Native-grid energy delegation with a zero-power gate for deterministic rejection tests.
     * @author howxu <dev@howxu.cn>
     */
    static final class EnergyMeter {
        IEnergyService delegate;
        boolean blocked;
        double charged;
        long charges;
        final IEnergyService service = (IEnergyService) Proxy.newProxyInstance(IEnergyService.class.getClassLoader(),
                new Class<?>[]{IEnergyService.class}, (proxy, method, args) -> {
                    boolean extraction = method.getName().equals("extractAEPower");
                    if (extraction && blocked) return 0D;
                    Object result;
                    try {
                        result = method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                    if (extraction && args[1] == Actionable.MODULATE) {
                        charged += (double) result;
                        charges++;
                    }
                    return result;
                });
    }
}
