package cn.howxu.mmcr.compat.ae2lt;

import cn.howxu.mmcr.api.compat.ae2lt.LightningTier;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier.IOType;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandlerRegistry;
import cn.howxu.mmcr.test.TestBootstrap;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Serialization and canonical recipe limits, independent of native lightning resources.
 * @author howxu <dev@howxu.cn>
 */
class LightningRecipeContractTest {
    private RequirementHandlerRegistry.TestScope requirements;
    private OutputRegistry.TestScope outputs;

    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrapCapabilities();
    }

    @BeforeEach
    void openRegistries() {
        requirements = RequirementHandlerRegistry.openTestScope();
        outputs = OutputRegistry.openTestScope();
        LightningRecipeTypes.register();
    }

    @AfterEach
    void closeRegistries() {
        outputs.close();
        requirements.close();
    }

    @Test
    void roundTripsBothTiersAndDirections() {
        for (LightningTier tier : LightningTier.values()) {
            for (IOType io : IOType.values()) {
                var original = new LightningRequirement(tier, io, 12345L, io.isInput() ? 1F : 0.25F, List.of("lane"));
                var encoded = MachineRequirement.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
                var decoded = (LightningRequirement) MachineRequirement.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
                assertThat(decoded).isEqualTo(original);
                assertThat(decoded.type()).isSameAs(LightningRecipeTypes.requirementType(tier));
                assertThat(decoded.type().capabilityIds()).containsExactly(LightningRecipeTypes.LIGHTNING);
                assertThat(encoded.getAsJsonObject().get("type").getAsString()).isEqualTo(tier.requirementId().toString());
                assertThat(encoded.getAsJsonObject().has("tier")).isFalse();
                var sync = decoded.type().syncCodec();
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    sync.encode(buffer, original);
                    assertThat(sync.decode(buffer)).isEqualTo(original);
                } finally {
                    buffer.release();
                }
            }
            JsonObject defaults = payload(tier, 9L);
            assertThat(MachineRequirement.CODEC.parse(JsonOps.INSTANCE, defaults).getOrThrow())
                    .isEqualTo(LightningRequirement.input(tier, 9L));
        }
    }

    @Test
    void customOutputConvertsToExecutionRequirement() {
        for (LightningTier tier : LightningTier.values()) {
            var output = new LightningOutput(tier, 567L, 0.75F);
            var encoded = MachineOutput.CODEC.encodeStart(JsonOps.INSTANCE, output).getOrThrow();
            var decoded = (LightningOutput) MachineOutput.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            assertThat(decoded).isEqualTo(output);
            assertThat(decoded.outputType()).isSameAs(LightningRecipeTypes.outputType(tier));
            var requirement = (LightningRequirement) OutputRegistry.toRequirement(decoded, List.of("output"));
            assertThat(requirement).isEqualTo(new LightningRequirement(tier, IOType.OUTPUT, 567L, 0.75F, List.of("output")));
            assertThat(OutputRegistry.fromRequirement(requirement)).isEqualTo(decoded);
            assertThat(OutputRegistry.matchesOutputRequirement(decoded, requirement)).isTrue();
            assertThat(OutputRegistry.fromRequirement(LightningRequirement.input(tier, 567L))).isNull();
            var recipe = recipe(List.of(requirement), List.of(decoded));
            assertThat(recipe.runtimeRequirements()).containsExactly(requirement);
            var recipeJson = MachineRecipe.CODEC.codec().encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
            assertThat(MachineRecipe.CODEC.codec().parse(JsonOps.INSTANCE, recipeJson).getOrThrow().runtimeRequirements())
                    .containsExactly(requirement);
        }
    }

    @Test
    void rejectsNonPositiveAndCapsOversizedDeclaration() {
        for (LightningTier tier : LightningTier.values()) {
            for (long amount : new long[]{0L, -1L, Long.MIN_VALUE}) {
                assertThatThrownBy(() -> LightningRequirement.input(tier, amount)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> new LightningOutput(tier, amount, 1F)).isInstanceOf(IllegalArgumentException.class);
                assertThat(MachineRequirement.CODEC.parse(JsonOps.INSTANCE, payload(tier, amount)).error()).isPresent();
                assertThat(MachineOutput.CODEC.parse(JsonOps.INSTANCE, payload(tier, amount)).error()).isPresent();
            }
            JsonObject oversized = payload(tier, Long.MAX_VALUE);
            var requirement = (LightningRequirement) MachineRequirement.CODEC.parse(JsonOps.INSTANCE, oversized).getOrThrow();
            var output = (LightningOutput) MachineOutput.CODEC.parse(JsonOps.INSTANCE, oversized).getOrThrow();
            assertThat(MachineRequirement.CODEC.encodeStart(JsonOps.INSTANCE, requirement).getOrThrow()
                    .getAsJsonObject().get("amount").getAsLong()).isEqualTo((long) Integer.MAX_VALUE);
            assertThat(MachineOutput.CODEC.encodeStart(JsonOps.INSTANCE, output).getOrThrow()
                    .getAsJsonObject().get("amount").getAsLong()).isEqualTo((long) Integer.MAX_VALUE);
            JsonObject chanceInput = payload(tier, 10L);
            chanceInput.addProperty("chance", 0.5F);
            assertThat(MachineRequirement.CODEC.parse(JsonOps.INSTANCE, chanceInput).error()).isPresent();
            assertThatThrownBy(() -> new LightningRequirement(tier, IOType.INPUT, 10L, 0.5F, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsCombinedSameTierOverflow() {
        var high = LightningRequirement.input(LightningTier.HIGH_VOLTAGE, Integer.MAX_VALUE);
        var another = LightningRequirement.input(LightningTier.HIGH_VOLTAGE, 1L);
        assertThatThrownBy(() -> recipe(List.of(high, another), List.of())).isInstanceOf(IllegalArgumentException.class);
        var output = new LightningOutput(LightningTier.HIGH_VOLTAGE, Integer.MAX_VALUE, 1F);
        var extra = new LightningOutput(LightningTier.HIGH_VOLTAGE, 1L, 1F);
        assertThatThrownBy(() -> recipe(List.of(), List.of(output, extra))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MachineRecipe.withAdditionalOutputs(recipe(List.of(), List.of(output)), List.of(extra)))
                .isInstanceOf(IllegalArgumentException.class);
        var represented = OutputRegistry.toRequirement(output, List.of());
        assertThat(recipe(List.of(represented), List.of(output)).runtimeRequirements()).containsExactly(represented);
        assertThat(recipe(List.of(high, LightningRequirement.input(LightningTier.EXTREME_HIGH_VOLTAGE, 1L),
                LightningRequirement.output(LightningTier.HIGH_VOLTAGE, 1L, 1F)), List.of()).runtimeRequirements()).hasSize(3);
    }

    @Test
    void validatesDerivedTotalsAfterOutputMultiplier() {
        var first = LightningRequirement.output(LightningTier.HIGH_VOLTAGE, 700_000_000L, 1F);
        var second = LightningRequirement.output(LightningTier.HIGH_VOLTAGE, 700_000_001L, 1F);
        var recipe = recipe(List.of(first, second), List.of());
        assertThatThrownBy(() -> recipe.runtimeRequirements(List.of(), 1D, 2D)).isInstanceOf(IllegalArgumentException.class);
        var modifier = new RecipeModifier("", IOType.OUTPUT, 2F, RecipeModifier.Operation.MULTIPLY, false);
        assertThatThrownBy(() -> recipe.runtimeRequirements(List.of(modifier))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outputChanceUsesExistingOutputNormalization() {
        var output = new LightningOutput(LightningTier.HIGH_VOLTAGE, 10L, 1F);
        assertThat(output.withChance(2F).chance()).isEqualTo(MachineOutput.clampChance(2F));
        assertThat(output.withChance(-1F).chance()).isEqualTo(MachineOutput.clampChance(-1F));
        assertThat(output.withChance(Float.NaN).chance()).isEqualTo(MachineOutput.clampChance(Float.NaN));
    }

    private static JsonObject payload(LightningTier tier, long amount) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", tier.requirementId().toString());
        payload.addProperty("amount", amount);
        return payload;
    }

    private static MachineRecipe recipe(List<MachineRequirement> requirements, List<MachineOutput> outputs) {
        return new MachineRecipe(Identifier.parse("test:lightning"), Identifier.parse("test:pool"), 20,
                requirements, outputs, List.of(), 0, 1, false, true, false, Set.of());
    }
}
