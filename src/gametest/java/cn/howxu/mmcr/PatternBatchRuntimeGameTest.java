package cn.howxu.mmcr;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.Machine;
import cn.howxu.mmcr.api.machine.MachineControllerSpec;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.machine.definition.RecipeBehavior;
import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.ParallelTier;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier.IOType;
import cn.howxu.mmcr.api.recipe.requirement.FluidRequirement;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.api.recipe.requirement.LevelRequirement;
import cn.howxu.mmcr.api.recipe.requirement.StageRequirement;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.machine.level.MachineLevelRegistry;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternBatchSupport;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestState;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.config.ServerConfig;
import cn.howxu.mmcr.internal.block.MachineControllerBlock;
import cn.howxu.mmcr.internal.event.SharedIoEvents;
import cn.howxu.mmcr.internal.multiblock.SharedIoCoordinator;
import cn.howxu.mmcr.internal.runtime.CraftingRuntime;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import cn.howxu.mmcr.internal.runtime.PatternStartReservation;
import cn.howxu.mmcr.internal.sync.RuntimeContentVersion;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerRuntime;
import cn.howxu.mmcr.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.howxu.mmcr.util.IOType.INPUT;

/** Real registered ports, root payments and explicit production IO rounds; no asynchronous tick assertions.
 * @author howxu <dev@howxu.cn>
 */
public final class PatternBatchRuntimeGameTest {
    private PatternBatchRuntimeGameTest() { }

    public static void exactNormalBatchRejectsPrefixAndFinishesOnce(GameTestHelper helper) {
        completeBatch(helper, false, 256L, false);
    }

    public static void exactFactoryBatchRejectsPrefixAndFinishesOnce(GameTestHelper helper) {
        completeBatch(helper, true, 256L, false);
    }

    public static void paidNormalPatternReloadKeepsSnapshot(GameTestHelper helper) {
        completeBatch(helper, false, 256L, true);
    }

    public static void paidFactoryPatternReloadKeepsSnapshot(GameTestHelper helper) {
        completeBatch(helper, true, 256L, true);
    }

    public static void exactLongItemAndFluidBatchFinishesOnce(GameTestHelper helper) {
        completeBatch(helper, false, Integer.MAX_VALUE, false);
    }

    public static void conditionalBatchRunsAllNativeCallbacksAtRequestedParallelism(GameTestHelper helper) {
        completeBatch(helper, false, 4L, false, true);
    }

    public static void inspectionDoesNotLoadLinkedControllerChunk(GameTestHelper helper) {
        try (var fixture = new Fixture(helper, false, 256L, false, "inspection")) {
            var unloaded = fixture.controller.getBlockPos().offset(1_000_000, 0, 0);
            helper.assertFalse(helper.getLevel().hasChunkAt(unloaded), "Remote linked controller starts in an unloaded chunk");
            fixture.host.linkControllerAppearance(unloaded, null);
            var definition = new ItemStack(Items.PAPER);
            AEProcessingPattern.encode(definition, List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2L),
                            new GenericStack(AEFluidKey.of(Fluids.WATER), 250L)),
                    List.of(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 3L),
                            new GenericStack(AEFluidKey.of(Fluids.LAVA), 100L)));
            var pattern = new AEProcessingPattern(AEItemKey.of(definition));
            helper.assertFalse(PatternBatchSupport.inspect(fixture.host, pattern).isEmpty(),
                    "A loaded eligible controller still supplies the binding");
            helper.assertFalse(helper.getLevel().hasChunkAt(unloaded), "Read-only inspection must not load remote controller chunks");
            fixture.controller.setRemoved();
            helper.assertTrue(PatternBatchSupport.inspect(fixture.host, pattern).isEmpty(),
                    "A removed controller may not advertise capacity through a stale association");
        }
        helper.succeed();
    }

    private static void completeBatch(GameTestHelper helper, boolean factory, long copies, boolean reload) {
        completeBatch(helper, factory, copies, reload, false);
    }

    private static void completeBatch(GameTestHelper helper, boolean factory, long copies, boolean reload, boolean guarded) {
        try (var fixture = new Fixture(helper, factory, copies, reload, "execution", guarded)) {
            var incomplete = fixture.request(copies, true);
            helper.assertTrue(fixture.reserve(copies, incomplete).status() == PatternStartReservation.Status.UNAVAILABLE,
                    "One missing item rejects the entire single-lane batch");
            helper.assertTrue(incomplete.inputs()[0].get(AEItemKey.of(Items.IRON_INGOT)) == Math.multiplyExact(2L, copies) - 1L
                            && incomplete.inputs()[0].get(AEFluidKey.of(Fluids.WATER)) == Math.multiplyExact(250L, copies),
                    "Rejected planning leaves actual item and fluid counters untouched");
            var request = fixture.request(copies, false);
            var reservation = fixture.reserve(copies, request);
            helper.assertTrue(reservation.status() == PatternStartReservation.Status.RESERVED && reservation.parallelism() == copies,
                    "A complete request reserves exactly one runtime at the requested long parallelism; max="
                            + fixture.controller.runtimeSnapshot().maxParallelism() + ", effective="
                            + fixture.controller.effectivePatternRecipe(fixture.recipe) + ", hooks=" + fixture.hooks.get());
            helper.assertTrue(reservation.commit(transaction -> {
                helper.assertTrue(request.items().accept(fixture.host.itemReturnStorage(), transaction)
                                && request.fluids().accept(fixture.host.fluidReturnStorage(), transaction),
                        "Both request families transfer ownership in the recipe's root transaction");
            }), "Complete payment commits without producing synchronously inside push");
            var runtime = fixture.activeRuntime();
            helper.assertTrue(runtime.parallelism() == copies && runtime.requiresPatternRequest()
                            && request.inputs()[0].get(AEItemKey.of(Items.IRON_INGOT)) == 0L
                            && request.inputs()[0].get(AEFluidKey.of(Fluids.WATER)) == 0L && fixture.returnedItems() == 0L,
                    "Payment owns the entire long batch and source constraint, but output is deferred");
            if (reload) fixture.reloadPaid();
            for (int round = 0; round < 8; round++) fixture.round();
            helper.assertTrue(fixture.returnedItems() == Math.multiplyExact(3L, copies)
                            && fixture.returnedFluids() == Math.multiplyExact(100L, copies),
                    "The saved effective item/fluid snapshot settles exactly once through native return inventory");
            helper.assertTrue(fixture.activeRuntimeOrNull() == null, "Completed source execution is not automatically restarted");
            helper.assertTrue(!fixture.tickParallelisms.isEmpty() && fixture.tickParallelisms.stream().allMatch(value -> value == copies)
                            && fixture.finishParallelisms.equals(List.of(copies)),
                    "Native tick and finish callbacks observe the whole batch parallelism and finish exactly once");
            for (int round = 0; round < 3; round++) fixture.round();
            helper.assertTrue(fixture.returnedItems() == Math.multiplyExact(3L, copies)
                            && fixture.returnedFluids() == Math.multiplyExact(100L, copies),
                    "Additional production rounds without new counters cannot duplicate outputs");
        }
        helper.succeed();
    }

    /** Owns a synchronous real-world fixture and restores registry/clock state on every exit.
     * @author howxu <dev@howxu.cn>
     */
    private static final class Fixture implements AutoCloseable {
        private final GameTestHelper helper;
        private final boolean factory;
        private final long originalTime;
        private final MachineWorkMode originalMode;
        private final Identifier machineId;
        private final Map<Identifier, MachineRecipe> previousRecipes;
        private final MachineControllerBlockEntity controller;
        private final MachineControllerRuntime runtime;
        private final PatternInterfaceBlockEntity host;
        private final MachineRecipe recipe;
        private final AtomicInteger hooks = new AtomicInteger();
        private final List<Long> tickParallelisms = new ArrayList<>();
        private final List<Long> finishParallelisms = new ArrayList<>();
        private boolean registered;

        private Fixture(GameTestHelper helper, boolean factory, long copies, boolean reload) {
            this(helper, factory, copies, reload, "execution");
        }

        private Fixture(GameTestHelper helper, boolean factory, long copies, boolean reload, String purpose) {
            this(helper, factory, copies, reload, purpose, false);
        }

        private Fixture(GameTestHelper helper, boolean factory, long copies, boolean reload, String purpose, boolean guarded) {
            this.helper = helper;
            this.factory = factory;
            originalTime = helper.getLevel().getGameTime();
            originalMode = ServerConfig.machineWorkMode();
            previousRecipes = RecipeRegistry.dynamicSnapshot();
            machineId = MMCR.id("batch_runtime_" + purpose + "_" + factory + "_" + copies + "_" + reload);
            BlockPos controllerPos = new BlockPos(0, 1, 0);
            BlockPos hostPos = new BlockPos(1, 1, 0);
            helper.setBlock(controllerPos, ModBlocks.controllerFor(MMCR.id("test_cube")).get().defaultBlockState()
                    .setValue(MachineControllerBlock.FACING, Direction.SOUTH)
                    .setValue(MachineControllerBlock.ROLL_FACING, Direction.NORTH));
            var hostBlock = ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get();
            helper.setBlock(hostPos, hostBlock.defaultBlockState());
            Map<BlockPos, BlockPredicate> pattern = new LinkedHashMap<>();
            pattern.put(hostPos.subtract(controllerPos), new BlockPredicate.OfBlock(hostBlock));
            var parallelBlock = ModBlocks.BLOCKS.get((copies <= 256L ? ParallelTier.PRO : ParallelTier.ULTIMATE).idSuffix()).get();
            helper.setBlock(controllerPos.offset(0, 0, 1), parallelBlock.defaultBlockState());
            pattern.put(new BlockPos(0, 0, 1), new BlockPredicate.OfBlock(parallelBlock));
            if (factory) {
                var scheduler = ModBlocks.BLOCKS.get("factory_controller").get();
                helper.setBlock(new BlockPos(2, 1, 0), scheduler.defaultBlockState());
                pattern.put(new BlockPos(2, 0, 0), new BlockPredicate.OfBlock(scheduler));
            }
            var machine = new Machine() {
                @Override public Identifier registryName() { return machineId; }
                @Override public BlockArray pattern() { return new BlockArray(pattern); }
                @Override public MachineControllerSpec controller() { return MachineControllerSpec.defaultsFor(machineId); }
                @Override public boolean parallelizable() { return true; }
                @Override public long maxParallelism() { return copies; }
                @Override public boolean hasFactory() { return factory; }
                @Override public int factoryThreadLimit() { return 4; }
                @Override public RecipeBehavior behavior() {
                    return RecipeBehavior.builder().beforeStart(context -> hooks.incrementAndGet())
                            .recipeTick(context -> tickParallelisms.add(context.parallelism()))
                            .beforeFinish(context -> finishParallelisms.add(context.effectiveParallelism())).build();
                }
            };
            controller = helper.getBlockEntity(controllerPos, MachineControllerBlockEntity.class);
            host = helper.getBlockEntity(hostPos, PatternInterfaceBlockEntity.class);
            try {
                var field = MachineControllerBlockEntity.class.getDeclaredField("runtime");
                field.setAccessible(true);
                runtime = (MachineControllerRuntime) field.get(controller);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
            try {
                MachineRegistry.register(machine);
                registered = true;
                controller.setMachine(machine);
                ServerConfig.MACHINE_WORK_MODE.set(MachineWorkMode.SYNC);
                SharedIoCoordinator.get(helper.getLevel()).beginExecutionRound(originalTime, true);
                controller.serverTick();
                helper.assertTrue(controller.currentStructureSnapshot().formed(), "Real fixture structure forms before admission");
                controller.setFormed(true);
                var requirements = new ArrayList<MachineRequirement>(List.of(
                                 new ItemRequirement(IOType.INPUT, Ingredient.of(Items.IRON_INGOT), 2, ItemStack.EMPTY),
                                 new FluidRequirement(IOType.INPUT, FluidIngredient.of(Fluids.WATER), 250, FluidStack.EMPTY),
                                 new ItemRequirement(IOType.OUTPUT, null, 0, new ItemStack(Items.GOLD_INGOT, 3)),
                                 new FluidRequirement(IOType.OUTPUT, null, 0, new FluidStack(Fluids.LAVA, 100))));
                if (guarded) {
                    var level = MachineLevelRegistry.levelsForType(MachineLevelRegistry.types().getFirst().id()).getFirst();
                    controller.componentRuntime().replaceLevels(Map.of(level.typeId(), level));
                    requirements.add(new LevelRequirement(level.typeId(), level.id()));
                    requirements.add(new StageRequirement(1));
                }
                recipe = MachineRecipe.fromCanonical(machineId.withSuffix("_recipe"), machineId, 2, requirements,
                        List.of(new MachineOutput.ItemOutput(new ItemStack(Items.GOLD_INGOT, 3), 1F),
                                new MachineOutput.FluidOutput(new FluidStack(Fluids.LAVA, 100), 1F)),
                        List.of(), 0, 1, false, true, false, Set.of());
                var recipes = new LinkedHashMap<>(previousRecipes);
                recipes.put(recipe.id(), recipe);
                RecipeRegistry.replaceDynamic(recipes);
            } catch (RuntimeException | Error failure) {
                close();
                throw new AssertionError("Cannot create pattern runtime fixture", failure);
            }
        }

        private Request request(long copies, boolean incomplete) {
            var counter = new KeyCounter();
            counter.add(AEItemKey.of(Items.IRON_INGOT), Math.multiplyExact(2L, copies) - (incomplete ? 1L : 0L));
            counter.add(AEFluidKey.of(Fluids.WATER), Math.multiplyExact(250L, copies));
            var inputs = new KeyCounter[]{counter};
            var state = new PatternRequestState(inputs, 2);
            return new Request(inputs, AE2ResourceFamilies.ITEM.patternRequestView(state),
                    AE2ResourceFamilies.FLUID.patternRequestView(state));
        }

        private PatternStartReservation reserve(long copies, Request request) {
            List<MachineCapability> capabilities = List.of(
                    AE2ResourceFamilies.ITEM.capabilityView(host, request.items(), INPUT, false),
                    AE2ResourceFamilies.FLUID.capabilityView(host, request.fluids(), INPUT, false));
            return controller.reserveExactPatternStart(host.getBlockPos(), recipe.id(),
                    recipe.runtimeMachineOutputs(), copies, capabilities);
        }

        private CraftingRuntime activeRuntimeOrNull() {
            return factory ? runtime.factoryRuntime().activeRuntimes().stream().findFirst().orElse(null)
                    : runtime.craftingRuntime().active() ? runtime.craftingRuntime() : null;
        }

        private CraftingRuntime activeRuntime() {
            var active = activeRuntimeOrNull();
            helper.assertTrue(active != null, "Exactly one paid runtime remains active");
            return active;
        }

        private long returnedItems() { return host.getLogic().getReturnInv().getAvailableStacks().get(AEItemKey.of(Items.GOLD_INGOT)); }
        private long returnedFluids() { return host.getLogic().getReturnInv().getAvailableStacks().get(AEFluidKey.of(Fluids.LAVA)); }

        private void reloadPaid() {
            var lookup = helper.getLevel().registryAccess();
            var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, lookup);
            if (factory) runtime.factoryRuntime().save(saved); else runtime.craftingRuntime().save(saved);
            controller.componentRuntime().setModifiersAllowed(true);
            controller.componentRuntime().replaceModifiers(Map.of("changed", List.of(
                    MachineModifier.numeric("output", "output", 2D, "multiply", false))));
            var input = TagValueInput.create(ProblemReporter.DISCARDING, lookup, saved.buildResult());
            if (factory) {
                runtime.factoryRuntime().load(input, controller);
                runtime.factoryRuntime().rebindCurrentVersions();
            } else {
                runtime.craftingRuntime().load(input, controller.resourceDomain());
                runtime.craftingRuntime().rebindCurrentVersions();
            }
            helper.assertTrue(activeRuntime().requiresPatternRequest(), "Paid reload retains request-only provenance");
        }

        private void round() {
            long next = helper.getLevel().getGameTime() + 1L;
            ((ServerLevelData) helper.getLevel().getLevelData()).setGameTime(next);
            SharedIoCoordinator.get(helper.getLevel()).beginExecutionRound(next, true);
            controller.serverTick();
            SharedIoEvents.completeLevelTick(helper.getLevel());
        }

        @Override public void close() {
            try {
                runtime.craftingRuntime().invalidate();
                runtime.factoryRuntime().clear();
                SharedIoCoordinator.get(helper.getLevel()).cancel(controller.getBlockPos());
                controller.releaseStructureClaims();
            } finally {
                ServerConfig.MACHINE_WORK_MODE.set(originalMode);
                ((ServerLevelData) helper.getLevel().getLevelData()).setGameTime(originalTime);
                RecipeRegistry.replaceDynamic(previousRecipes);
                if (registered) removeMachine();
            }
        }

        @SuppressWarnings("unchecked")
        private void removeMachine() {
            synchronized (RuntimeContentVersion.lock()) {
                try {
                    for (String name : List.of("STATIC_MACHINES", "COMPILED", "EFFECTIVE_MACHINES")) {
                        var field = MachineRegistry.class.getDeclaredField(name);
                        field.setAccessible(true);
                        var current = (Map<Identifier, Object>) field.get(null);
                        if (name.equals("STATIC_MACHINES")) current.remove(machineId);
                        else {
                            var copy = new LinkedHashMap<>(current);
                            copy.remove(machineId);
                            field.set(null, Map.copyOf(copy));
                        }
                    }
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            }
        }
    }

    /** @author howxu <dev@howxu.cn> */
    private record Request(KeyCounter[] inputs, PatternRequestResourceStorage<ItemResource> items,
                           PatternRequestResourceStorage<FluidResource> fluids) { }
}
