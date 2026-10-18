package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import cn.howxu.mmcr.api.recipe.EffectiveRecipe;
import cn.howxu.mmcr.api.recipe.EffectiveRecipeResolver;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier.IOType;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.recipe.requirement.StageRequirement;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternBatchSupport;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.PatternBatchSupport.Safety;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.pattern.PatternBatchBinding;
import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;
import cn.howxu.mmcr.internal.runtime.FactorySnapshot;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Binding and capacity proofs use effective requirements, not output names or thread multiplication.
 * @author howxu <dev@howxu.cn>
 */
class PatternBatchSupportTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrap();
        TestBootstrap.bootstrapCapabilities();
    }

    @Test
    void profileUsesEffectiveRecipeAndOneLaneOnly() {
        var recipe = recipe("profile", List.of(input()), List.of(output(1)), true);
        var pattern = pattern(1, List.of(stack(Items.GOLD_INGOT, 1)));
        var snapshot = snapshot(256L);
        var resolver = new EffectiveRecipeResolver();
        var effective = resolver.resolve(recipe, snapshot, List.of());
        assertThat(PatternBatchSupport.inspectRecipe(recipe, effective, pattern).maxMultiplier()).isEqualTo(256L);
        var disabled = resolver.resolve(recipe, snapshot, List.of(new MachineModifier.Parallelized(false)));
        assertThat(PatternBatchSupport.inspectRecipe(recipe, disabled, pattern).maxMultiplier()).isOne();
        var serial = recipe("serial", List.of(input()), List.of(output(1)), false);
        assertThat(PatternBatchSupport.inspectRecipe(serial, resolver.resolve(serial, snapshot, List.of()), pattern)
                .maxMultiplier()).isOne();
        var enabled = resolver.resolve(serial, snapshot, List.of(new MachineModifier.Parallelized(true)));
        assertThat(PatternBatchSupport.inspectRecipe(serial, enabled, pattern).maxMultiplier()).isEqualTo(256L);
    }

    @Test
    void patternUnitAndRecipeCraftsAreNotInterchangeable() {
        var recipe = recipe("two_crafts", List.of(input()), List.of(output(1)), true);
        var effective = new EffectiveRecipeResolver().resolve(recipe, snapshot(256L), List.of());
        var original = pattern(20, List.of(stack(Items.GOLD_INGOT, 2)));
        var profile = PatternBatchSupport.inspectRecipe(recipe, effective, original);
        assertThat(profile.binding().recipeCraftsPerPattern()).isEqualTo(2L);
        assertThat(profile.maxMultiplier()).isEqualTo(128L);
        assertThat(PatternBatchSupport.matches(profile.binding(), recipe, effective, original)).isTrue();
        assertThat(PatternBatchSupport.matches(new PatternBatchBinding(MMCR.id("other_pool"), recipe.id(), 2L),
                recipe, effective, original)).isFalse();
        var wrongInput = patternFor(AEItemKey.of(Items.DIAMOND), 20L, original.getOutputs());
        assertThat(PatternBatchSupport.inspectRecipe(recipe, effective, wrongInput)).isNull();
        var named = new ItemStack(Items.GOLD_INGOT);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("different component"));
        assertThat(PatternBatchSupport.inspectRecipe(recipe, effective,
                pattern(20, List.of(new GenericStack(AEItemKey.of(named), 2L))))).isNull();
    }

    @Test
    void conflictingByproductRatiosCannotBind() {
        var recipe = recipe("byproducts", List.of(input()), List.of(output(1),
                new MachineOutput.ItemOutput(new ItemStack(Items.DIAMOND), 1F)), true);
        var effective = new EffectiveRecipeResolver().resolve(recipe, snapshot(256L), List.of());
        assertThat(PatternBatchSupport.inspectRecipe(recipe, effective,
                pattern(10, List.of(stack(Items.GOLD_INGOT, 2), stack(Items.DIAMOND, 3))))).isNull();
    }

    @Test
    void unknownRequirementIsNotDisguisedAsACapacityOfOne() {
        var ordinary = recipe("known_one", List.of(input()), List.of(output(1)), true);
        var unknown = recipe("unknown_one", List.of(input(), new StageRequirement(1)), List.of(output(1)), true);
        var resolver = new EffectiveRecipeResolver();
        var pattern = pattern(1, List.of(stack(Items.GOLD_INGOT, 1)));
        var knownProfile = PatternBatchSupport.inspectRecipe(ordinary, resolver.resolve(ordinary, snapshot(1), List.of()), pattern);
        var unknownProfile = PatternBatchSupport.inspectRecipe(unknown, resolver.resolve(unknown, snapshot(1), List.of()), pattern);
        assertThat(knownProfile.safety()).isEqualTo(Safety.PROVEN_LINEAR);
        assertThat(unknownProfile.safety()).isEqualTo(Safety.UNPROVEN);
        assertThat(unknownProfile.maxMultiplier()).isEqualTo(knownProfile.maxMultiplier());
    }

    @Test
    void sameKeyTotalsBoundMultipliersWithoutFloatQuantization() {
        var recipe = recipe("long_inputs", List.of(input()), List.of(output(1)), true);
        var effective = new EffectiveRecipeResolver().resolve(recipe, snapshot(Integer.MAX_VALUE), List.of());
        var original = patternFor(AEItemKey.of(Items.IRON_INGOT), Long.MAX_VALUE / 2L + 1L,
                List.of(stack(Items.GOLD_INGOT, 1)));
        var profile = PatternBatchSupport.inspectRecipe(recipe, effective, original);
        assertThat(profile.maxMultiplier()).isOne();
        var small = PatternBatchSupport.inspectRecipe(recipe, effective, pattern(1, List.of(stack(Items.GOLD_INGOT, 1))));
        assertThat(small.maxMultiplier()).isEqualTo((long) Integer.MAX_VALUE);
    }

    private static MachineRecipe recipe(String name, List<MachineRequirement> requirements,
                                         List<MachineOutput> outputs, boolean parallelized) {
        List<MachineRequirement> canonical = new ArrayList<>(requirements);
        for (MachineOutput output : outputs) canonical.add(OutputRegistry.tryToRequirement(output, List.of()));
        return MachineRecipe.fromCanonical(MMCR.id(name), MMCR.id("test_cube"), 20, canonical,
                outputs, List.of(), 0, 1, false, parallelized, false, Set.of());
    }

    private static ItemRequirement input() {
        return new ItemRequirement(IOType.INPUT, Ingredient.of(Items.IRON_INGOT), 1, ItemStack.EMPTY);
    }

    private static MachineOutput output(int amount) {
        return new MachineOutput.ItemOutput(new ItemStack(Items.GOLD_INGOT, amount), 1F);
    }

    private static GenericStack stack(Item item, long amount) {
        return new GenericStack(AEItemKey.of(item), amount);
    }

    private static IPatternDetails pattern(long inputAmount, List<GenericStack> outputs) {
        return patternFor(AEItemKey.of(Items.IRON_INGOT), inputAmount, outputs);
    }

    private static IPatternDetails patternFor(AEKey inputKey, long amount, List<GenericStack> outputs) {
        return new IPatternDetails() {
            @Override public AEItemKey getDefinition() { return AEItemKey.of(Items.PAPER); }
            @Override public List<GenericStack> getOutputs() { return outputs; }
            @Override public IInput[] getInputs() {
                return new IInput[]{new IInput() {
                    @Override public GenericStack[] getPossibleInputs() { return new GenericStack[]{new GenericStack(inputKey, amount)}; }
                    @Override public long getMultiplier() { return 1L; }
                    @Override public boolean isValid(AEKey input, Level level) { return inputKey.equals(input); }
                    @Override public AEKey getRemainingKey(AEKey template) { return null; }
                }};
            }
        };
    }

    private static ControllerRuntimeSnapshot snapshot(long parallelism) {
        var source = RuntimeTestFixtures.controller(MMCR.id("test_cube")).currentRuntimeSnapshot();
        return new ControllerRuntimeSnapshot(source.structure(), source.capabilityVersion(), source.modifierVersion(),
                source.stateVersion(), source.foundModifiers(), source.foundLevels(), source.linkedPortPositions(),
                source.moduleConnectionStatus(), source.installedModuleCount(), source.crafting(),
                new FactorySnapshot(true, false, List.of(), 4, 0, parallelism, false, List.of(), "", 0, null, List.of(), 0, 1),
                source.componentPresentations(), source.capabilityPresentations(), source.foundLevelIds(), source.machineId(),
                source.machineName(), source.controllerRole(), true, true, 0, parallelism, parallelism);
    }
}
