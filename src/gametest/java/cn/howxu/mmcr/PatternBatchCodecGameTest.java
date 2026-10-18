package cn.howxu.mmcr;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.core.definitions.AEBlockEntities;
import appeng.core.definitions.AEBlocks;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingPlan;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.crafting.pattern.AEPatternDecoder;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.MachineSource;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.pattern.PatternBatchDetails;
import cn.howxu.mmcr.internal.item.PatternBatchData;
import cn.howxu.mmcr.registry.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Exercises the actual AE definition codec, input extraction and native CPU/job persistence.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class PatternBatchCodecGameTest {
    private static final Identifier POOL = MMCR.id("pattern_batch_codec_test");
    private static final Identifier RECIPE = MMCR.id("pattern_batch_codec_recipe");

    private PatternBatchCodecGameTest() {
    }

    public static void batchDefinitionRoundTripsDistinctMultipliers(GameTestHelper helper) {
        IPatternDetails original = original(helper);
        AEItemKey physical = original.getDefinition();
        PatternBatchDetails unit = new PatternBatchDetails(original, data(1));
        PatternBatchDetails full = unit.scaled(256);
        PatternBatchDetails remainder = full.scaled(16);
        PatternBatchDetails restoredFull = restore(helper, full);
        PatternBatchDetails restoredRemainder = restore(helper, remainder);

        helper.assertTrue(full.equals(restoredFull) && full.hashCode() == restoredFull.hashCode()
                        && remainder.equals(restoredRemainder) && remainder.hashCode() == restoredRemainder.hashCode()
                        && !full.equals(remainder) && !full.getDefinition().equals(remainder.getDefinition()),
                "Native AE definition serialization preserves two distinct multiplier task identities");
        helper.assertTrue(full.unitPattern().equals(unit) && remainder.unitPattern().equals(unit)
                        && !unit.equals(original),
                "Provider lookup can return to the same bound unit without collapsing to the physical pattern");
        helper.assertTrue(physical.equals(original.getDefinition())
                        && physical.get(ModDataComponents.PATTERN_BATCH.get()) == null
                        && restoredFull.original().getDefinition().equals(physical)
                        && restoredRemainder.original().getDefinition().equals(physical),
                "Virtual copies preserve the original pattern and every unrelated item component");
        helper.assertTrue(AEPatternDecoder.INSTANCE.decodePattern(physical, helper.getLevel()).equals(original),
                "The native unmarked decoder is unchanged");

        extractAndReinject(helper, original, restoredFull, 256);
        extractAndReinject(helper, original, restoredRemainder, 16);
        // The definition and extraction path must not impose an arbitrary small/int multiplier cap.
        extractAndReinject(helper, original, restore(helper, unit.scaled(2_147_483_647L)), 2_147_483_647L);
        helper.succeed();
    }

    public static void invalidBatchDefinitionCannotBecomeAnUnscaledTask(GameTestHelper helper) {
        IPatternDetails original = original(helper);
        assertInvalid(helper, original, marked(original.getDefinition(),
                new PatternBatchData(2, POOL, RECIPE, 1, 256)));
        assertInvalid(helper, original, marked(original.getDefinition(), data(Long.MAX_VALUE)));
        assertInvalid(helper, original, marked(original.getDefinition(),
                new PatternBatchData(1, POOL, RECIPE, Long.MAX_VALUE, 2)));
        assertInvalid(helper, original, marked(original.getDefinition(),
                new PatternBatchData(1, POOL, MMCR.id("missing_batch_recipe"), 1, 256)));
        assertInvalid(helper, original, marked(original.getDefinition(),
                new PatternBatchData(1, MMCR.id("wrong_batch_pool"), RECIPE, 1, 256)));
        assertInvalid(helper, original, marked(AEItemKey.of(Items.STONE), data(256)));
        assertMalformedMarker(helper, original, marker -> marker.putLong("multiplier", 0));
        assertMalformedMarker(helper, original, marker -> marker.putLong("recipe_crafts_per_pattern", -1));
        assertMalformedMarker(helper, original, marker -> marker.remove("recipe"));
        assertMalformedMarker(helper, original, marker -> marker.putString("version", "broken"));

        PatternBatchDetails unit = new PatternBatchDetails(original, data(1));
        boolean rejectedNested = false;
        try {
            new PatternBatchDetails(unit, data(256));
        } catch (IllegalArgumentException expected) {
            rejectedNested = true;
        }
        helper.assertTrue(rejectedNested && unit.getDefinition().get(ModDataComponents.PATTERN_BATCH.get()).equals(data(1))
                        && original.getDefinition().get(ModDataComponents.PATTERN_BATCH.get()) == null,
                "Nested wrapping is rejected without mutating either base or unit identity");
        helper.succeed();
    }

    public static void nativeExecutingJobReloadKeepsFullAndRemainder(GameTestHelper helper) {
        IPatternDetails original = original(helper);
        PatternBatchDetails full = new PatternBatchDetails(original, data(256));
        PatternBatchDetails remainder = full.scaled(16);
        Map<IPatternDetails, Long> tasks = Map.of(full, 39L, remainder, 1L);
        CraftingCpuLogic cpu = nativeJob(helper, tasks);
        KeyCounter materials = new KeyCounter();
        for (IPatternDetails.IInput input : original.getInputs()) {
            GenericStack template = input.getPossibleInputs()[0];
            long amount = Math.multiplyExact(Math.multiplyExact(template.amount(), input.getMultiplier()), 10_000L);
            materials.add(template.what(), amount);
            cpu.getInventory().insert(template.what(), amount, Actionable.MODULATE);
        }
        TagValueOutput saved = output(helper);
        cpu.writeToNBT(saved);
        CraftingCpuLogic restored = cluster(helper).craftingLogic;
        restored.readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), saved.buildResult()));

        helper.assertTrue(taskCounts(restored).equals(tasks) && restored.hasJob(),
                "Native ExecutingCraftingJob reload keeps full 256x39 and remainder 16x1 tasks without overwriting");
        for (var entry : materials) {
            helper.assertTrue(cpu.getStored(entry.getKey()) == entry.getLongValue()
                            && restored.getStored(entry.getKey()) == entry.getLongValue(),
                    "Undispatched item/fluid materials remain owned by the native CPU across reload");
        }
        for (GenericStack expected : original.getOutputs()) {
            helper.assertTrue(restored.getWaitingFor(expected.what()) == 0,
                    "Saving an undispatched task must not invent an accepted batch or waiting output");
        }
        // Re-save restored tasks: their reconstructed definitions must remain independently decodable.
        TagValueOutput secondSave = output(helper);
        restored.writeToNBT(secondSave);
        CraftingCpuLogic twiceRestored = cluster(helper).craftingLogic;
        twiceRestored.readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), secondSave.buildResult()));
        helper.assertTrue(taskCounts(twiceRestored).equals(tasks),
                "A second native save/reload does not collapse the reconstructed batch definitions");
        for (var entry : materials) {
            helper.assertTrue(twiceRestored.getStored(entry.getKey()) == entry.getLongValue(),
                    "Repeated reload neither dispatches nor duplicates the native CPU material balance");
        }
        helper.succeed();
    }

    private static IPatternDetails original(GameTestHelper helper) {
        if (!MachineRegistry.containsStatic(POOL)) {
            MachineRegistry.register(new DynamicMachine(POOL, "Pattern batch codec test", new BlockArray(Map.of())));
        }
        if (RecipeRegistry.getRecipe(RECIPE) == null) {
            RecipeRegistry.registerStatic(MachineRecipe.fromCanonical(RECIPE, POOL, 20,
                    List.of(), List.of(), List.of(), 0, 1, false, true, false, Set.of()));
        }
        ItemStack iron = new ItemStack(Items.IRON_INGOT);
        iron.set(DataComponents.CUSTOM_NAME, Component.literal("Batch input components"));
        ItemStack pattern = PatternDetailsHelper.encodeProcessingPattern(
                List.of(new GenericStack(AEItemKey.of(iron), 3), new GenericStack(AEFluidKey.of(Fluids.WATER), 1000)),
                List.of(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 2), new GenericStack(AEFluidKey.of(Fluids.LAVA), 500)));
        pattern.set(DataComponents.CUSTOM_NAME, Component.literal("Physical pattern components"));
        IPatternDetails original = PatternDetailsHelper.decodePattern(pattern, helper.getLevel());
        helper.assertTrue(original != null, "The physical processing pattern decodes before wrapping");
        return original;
    }

    private static PatternBatchData data(long multiplier) {
        return new PatternBatchData(1, POOL, RECIPE, 1, multiplier);
    }

    private static AEItemKey marked(AEItemKey original, PatternBatchData data) {
        ItemStack stack = original.toStack();
        stack.set(ModDataComponents.PATTERN_BATCH.get(), data);
        return AEItemKey.of(stack);
    }

    private static PatternBatchDetails restore(GameTestHelper helper, PatternBatchDetails pattern) {
        AEItemKey definition = roundTrip(helper, pattern.getDefinition());
        IPatternDetails decoded = AEPatternDecoder.INSTANCE.decodePattern(definition, helper.getLevel());
        helper.assertTrue(decoded instanceof PatternBatchDetails,
                "The original AE decoder claims the marker before native processing-pattern decoding");
        helper.assertTrue(decoded.equals(PatternDetailsHelper.decodePattern(definition, helper.getLevel())),
                "Direct and standard AE decoding use the same virtual definition protocol");
        return (PatternBatchDetails) decoded;
    }

    private static AEItemKey roundTrip(GameTestHelper helper, AEItemKey definition) {
        TagValueOutput output = output(helper);
        definition.toTag(output);
        AEItemKey restored = AEItemKey.fromTag(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), output.buildResult()));
        helper.assertTrue(definition.equals(restored), "AEItemKey's native codec preserves all definition components");
        return restored;
    }

    private static void extractAndReinject(GameTestHelper helper, IPatternDetails original,
                                           PatternBatchDetails batch, long multiplier) {
        ListCraftingInventory inventory = new ListCraftingInventory(key -> {});
        KeyCounter supplied = new KeyCounter();
        for (int i = 0; i < original.getInputs().length; i++) {
            var input = original.getInputs()[i];
            var scaled = batch.getInputs()[i];
            GenericStack template = input.getPossibleInputs()[0];
            helper.assertTrue(List.of(input.getPossibleInputs()).equals(List.of(scaled.getPossibleInputs()))
                            && scaled.isValid(template.what(), helper.getLevel())
                            && scaled.getMultiplier() == Math.multiplyExact(input.getMultiplier(), multiplier),
                    "Scaling preserves input alternatives/components and multiplies the original input quantity");
            long amount = Math.multiplyExact(template.amount(), scaled.getMultiplier());
            supplied.add(template.what(), amount);
            inventory.insert(template.what(), amount, Actionable.MODULATE);
        }
        KeyCounter expectedOutputs = new KeyCounter();
        KeyCounter containers = new KeyCounter();
        KeyCounter[] extracted = CraftingCpuHelper.extractPatternInputs(batch, inventory, helper.getLevel(), expectedOutputs, containers);
        helper.assertTrue(extracted != null && inventory.list.isEmpty(),
                "Native AE extraction consumes exactly the entire scaled item/fluid request, including beyond-int amounts");
        for (GenericStack output : original.getOutputs()) {
            helper.assertTrue(expectedOutputs.get(output.what()) == Math.multiplyExact(output.amount(), multiplier),
                    "Native AE extraction registers every output with the same multiplier");
        }
        CraftingCpuHelper.reinjectPatternInputs(inventory, extracted);
        for (var entry : supplied) {
            helper.assertTrue(inventory.list.get(entry.getKey()) == entry.getLongValue(),
                    "Native rejection/reinjection restores the exact component-sensitive input balances");
        }
    }

    private static void assertInvalid(GameTestHelper helper, IPatternDetails original, AEItemKey invalid) {
        AEItemKey persisted = roundTrip(helper, invalid);
        boolean rejected = false;
        try {
            PatternDetailsHelper.decodePattern(persisted, helper.getLevel());
        } catch (IllegalArgumentException | ArithmeticException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "An invalid marked definition fails explicitly, never becoming an unscaled pattern");

        // Store the damaged definition through a real native task, then exercise the native restore entry point.
        IPatternDetails damaged = new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return persisted; }
            @Override public IInput[] getInputs() { return original.getInputs(); }
            @Override public List<GenericStack> getOutputs() { return original.getOutputs(); }
        };
        CraftingCpuLogic cpu = nativeJob(helper, Map.of(damaged, 1L));
        TagValueOutput saved = output(helper);
        cpu.writeToNBT(saved);
        boolean rejectedRestore = false;
        try {
            cluster(helper).craftingLogic.readFromNBT(TagValueInput.create(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess(), saved.buildResult()));
        } catch (IllegalArgumentException | ArithmeticException expected) {
            rejectedRestore = true;
        }
        helper.assertTrue(rejectedRestore && taskCounts(cpu).get(damaged) == 1L,
                "Native CPU load reports a damaged task rather than dropping or unscaling it; source task remains intact");
    }

    private static void assertMalformedMarker(GameTestHelper helper, IPatternDetails original,
                                               Consumer<CompoundTag> corrupt) {
        TagValueOutput saved = output(helper);
        marked(original.getDefinition(), data(256)).toTag(saved);
        CompoundTag tag = saved.buildResult();
        corrupt.accept(tag.getCompoundOrEmpty("components").getCompoundOrEmpty("mmcr:pattern_batch"));
        AEItemKey restored = AEItemKey.fromTag(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), tag));
        helper.assertTrue(restored != null && restored.get(ModDataComponents.PATTERN_BATCH.get()) != null,
                "Malformed component data must not become a partial AE key with the batch marker removed");
        assertInvalid(helper, original, restored);
    }

    private static CraftingCpuLogic nativeJob(GameTestHelper helper, Map<IPatternDetails, Long> tasks) {
        CraftingCPUCluster cluster = cluster(helper);
        CraftingPlan plan = new CraftingPlan(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 20_000),
                1, false, false, new KeyCounter(), new KeyCounter(), new KeyCounter(), tasks);
        try {
            Class<?> listener = Class.forName(ExecutingCraftingJob.class.getName() + "$CraftingDifferenceListener");
            var constructor = ExecutingCraftingJob.class.getDeclaredConstructor(ICraftingPlan.class, listener,
                    CraftingLink.class, Integer.class);
            constructor.setAccessible(true);
            Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (proxy, method, args) -> null);
            Object job = constructor.newInstance(plan, callback, new CraftingLink(UUID.randomUUID(), true, cluster), null);
            field(CraftingCpuLogic.class, "job").set(cluster.craftingLogic, job);
            return cluster.craftingLogic;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot construct the native AE executing job fixture", exception);
        }
    }

    private static Map<IPatternDetails, Long> taskCounts(CraftingCpuLogic cpu) {
        try {
            Object job = field(CraftingCpuLogic.class, "job").get(cpu);
            Map<?, ?> tasks = (Map<?, ?>) field(ExecutingCraftingJob.class, "tasks").get(job);
            Map<IPatternDetails, Long> counts = new HashMap<>();
            for (var entry : tasks.entrySet()) {
                counts.put((IPatternDetails) entry.getKey(), field(entry.getValue().getClass(), "value").getLong(entry.getValue()));
            }
            return counts;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot inspect the native AE task map", exception);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static CraftingCPUCluster cluster(GameTestHelper helper) {
        // A detached native core supplies the real level without ticking/dispatching the saved job.
        BlockPos pos = helper.absolutePos(BlockPos.ZERO);
        CraftingCPUCluster cluster = new CraftingCPUCluster(pos, pos);
        CraftingBlockEntity core = new CraftingBlockEntity(AEBlockEntities.CRAFTING_STORAGE.get(), pos,
                AEBlocks.CRAFTING_STORAGE_64K.block().defaultBlockState());
        core.setLevel(helper.getLevel());
        core.setCoreBlock(true);
        try {
            field(CraftingCPUCluster.class, "machineSrc").set(cluster, new MachineSource(core));
            field(CraftingBlockEntity.class, "cluster").set(core, cluster);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot bind the native AE CPU core fixture", exception);
        }
        helper.assertTrue(cluster.getLevel() == helper.getLevel() && core.getCluster() == cluster,
                "Native CPU level resolution uses the real core block entity and its bound cluster");
        return cluster;
    }

    private static TagValueOutput output(GameTestHelper helper) {
        return TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
    }
}
