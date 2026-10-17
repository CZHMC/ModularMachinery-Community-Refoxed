package cn.howxu.mmcr.compat.kubejs;

import cn.howxu.mmcr.api.compat.ae2lt.LightningTier;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier.IOType;
import cn.howxu.mmcr.compat.ae2lt.LightningOutput;
import cn.howxu.mmcr.compat.ae2lt.LightningRecipeTypes;
import cn.howxu.mmcr.compat.ae2lt.LightningRequirement;
import cn.howxu.mmcr.test.TestBootstrap;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.latvian.mods.kubejs.recipe.KubeRecipe;
import dev.latvian.mods.kubejs.recipe.RecipeScriptContext;
import dev.latvian.mods.kubejs.util.ErrorStack;
import dev.latvian.mods.rhino.Context;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author howxu <dev@howxu.cn>
 */
class LightningRecipeBuilderJSTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrap();
        LightningRecipeTypes.register();
    }

    @Test
    void customBuilderRoundTripsLightningInputsAndOutputs() {
        MachineRecipe built = new MachineRecipeBuilderJS("mmcr:lightning_custom_script")
                .recipePool("mmcr:test_cube")
                .custom("ae2lt:high_voltage", IOType.INPUT, payload("ae2lt:high_voltage", 2_147_483_647L, 1F))
                .custom("ae2lt:extreme_high_voltage", IOType.INPUT, payload("ae2lt:extreme_high_voltage", 1_234_567L, 1F))
                .custom("ae2lt:high_voltage", IOType.OUTPUT, payload("ae2lt:high_voltage", 765_432L, 0.5F))
                .custom("ae2lt:extreme_high_voltage", IOType.OUTPUT, payload("ae2lt:extreme_high_voltage", 1_987_654L, 1F))
                .createObject();

        var codec = MachineRecipe.CODEC.codec();
        MachineRecipe decoded = codec.parse(JsonOps.INSTANCE,
                codec.encodeStart(JsonOps.INSTANCE, built).getOrThrow()).getOrThrow();

        assertThat(decoded.machineOutputs()).containsExactly(
                new LightningOutput(LightningTier.HIGH_VOLTAGE, 765_432L, 0.5F),
                new LightningOutput(LightningTier.EXTREME_HIGH_VOLTAGE, 1_987_654L, 1F));
        assertThat(decoded.runtimeRequirements()).containsExactlyInAnyOrder(
                LightningRequirement.input(LightningTier.HIGH_VOLTAGE, 2_147_483_647L),
                LightningRequirement.input(LightningTier.EXTREME_HIGH_VOLTAGE, 1_234_567L),
                LightningRequirement.output(LightningTier.HIGH_VOLTAGE, 765_432L, 0.5F),
                LightningRequirement.output(LightningTier.EXTREME_HIGH_VOLTAGE, 1_987_654L, 1F));
        assertThat(decoded.runtimeRequirements()).allSatisfy(requirement -> {
            var lightning = (LightningRequirement) requirement;
            assertThat(lightning.type()).isSameAs(LightningRecipeTypes.requirementType(lightning.tier()));
        });
    }

    @Test
    void shortcutsAndSchemaProduceMatchingCanonicalLightningRecipes() {
        var builder = new MachineRecipeBuilderJS("mmcr:lightning_shortcuts").recipePool("mmcr:test_cube");
        assertThat(builder.lightningInput("ae2lt:high_voltage", 123L)).isSameAs(builder);
        assertThat(builder.lightningInput("ae2lt:extreme_high_voltage", 456L)).isSameAs(builder);
        assertThat(builder.lightningOutput("ae2lt:high_voltage", 78L)).isSameAs(builder);
        assertThat(builder.lightningOutputChance("ae2lt:extreme_high_voltage", 90L, 0.25D)).isSameAs(builder);
        var built = builder.createObject();
        var schema = schemaRecipe();
        execute(schema, "lightningInput", List.of("ae2lt:high_voltage", 123L));
        execute(schema, "lightningInput", List.of("ae2lt:extreme_high_voltage", 456L));
        execute(schema, "lightningOutput", List.of("ae2lt:high_voltage", 78L));
        execute(schema, "lightningOutputChance", List.of("ae2lt:extreme_high_voltage", 90L, 0.25D));
        var decoded = MachineRecipe.CODEC.codec().parse(JsonOps.INSTANCE, schema.json).getOrThrow();

        var expectedRequirements = List.of(
                LightningRequirement.input(LightningTier.HIGH_VOLTAGE, 123L),
                LightningRequirement.input(LightningTier.EXTREME_HIGH_VOLTAGE, 456L),
                LightningRequirement.output(LightningTier.HIGH_VOLTAGE, 78L, 1F),
                LightningRequirement.output(LightningTier.EXTREME_HIGH_VOLTAGE, 90L, 0.25F));
        var expectedOutputs = List.of(
                new LightningOutput(LightningTier.HIGH_VOLTAGE, 78L, 1F),
                new LightningOutput(LightningTier.EXTREME_HIGH_VOLTAGE, 90L, 0.25F));
        assertThat(built.runtimeRequirements()).containsExactlyElementsOf(expectedRequirements);
        assertThat(decoded.runtimeRequirements()).containsExactlyElementsOf(expectedRequirements);
        assertThat(built.machineOutputs()).containsExactlyElementsOf(expectedOutputs);
        assertThat(decoded.machineOutputs()).containsExactlyElementsOf(expectedOutputs);
    }

    @Test
    void shortcutsAndSchemaUseCanonicalAmountAndProbabilityClamping() {
        var built = new MachineRecipeBuilderJS("mmcr:lightning_limits").recipePool("mmcr:test_cube")
                .lightningInput("ae2lt:high_voltage", Long.MAX_VALUE)
                .lightningOutput("ae2lt:extreme_high_voltage", Long.MAX_VALUE, 2D)
                .createObject();
        var schema = schemaRecipe();
        execute(schema, "lightningInput", List.of("ae2lt:high_voltage", Long.MAX_VALUE));
        execute(schema, "lightningOutputChance", List.of("ae2lt:extreme_high_voltage", Long.MAX_VALUE, 2D));
        var decoded = MachineRecipe.CODEC.codec().parse(JsonOps.INSTANCE, schema.json).getOrThrow();

        assertThat(built.runtimeRequirements()).containsExactly(
                LightningRequirement.input(LightningTier.HIGH_VOLTAGE, Integer.MAX_VALUE),
                LightningRequirement.output(LightningTier.EXTREME_HIGH_VOLTAGE, Integer.MAX_VALUE, 1F));
        assertThat(decoded.runtimeRequirements()).containsExactlyElementsOf(built.runtimeRequirements());
        assertThat(decoded.machineOutputs()).containsExactly(
                new LightningOutput(LightningTier.EXTREME_HIGH_VOLTAGE, Integer.MAX_VALUE, 1F));
    }

    @Test
    void shortcutsAndSchemaRejectUnknownTiersAndNonPositiveAmountsWithoutChangingRecipe() {
        var builder = new MachineRecipeBuilderJS("mmcr:lightning_invalid").recipePool("mmcr:test_cube");
        var schema = schemaRecipe();
        var unchanged = schema.json.deepCopy();
        for (String tier : List.of("ae2lt:unknown", "minecraft:high_voltage")) {
            assertThatThrownBy(() -> builder.lightningInput(tier, 1L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.lightningOutput(tier, 1L)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> execute(schema, "lightningInput", List.of(tier, 1L)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> execute(schema, "lightningOutput", List.of(tier, 1L)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> execute(schema, "lightningOutputChance", List.of(tier, 1L, 0.5D)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String tier : List.of("ae2lt:high_voltage", "ae2lt:extreme_high_voltage")) {
            for (long amount : new long[] {0L, -1L}) {
                assertThatThrownBy(() -> builder.lightningInput(tier, amount)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> builder.lightningOutput(tier, amount, 0.5D)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> execute(schema, "lightningInput", List.of(tier, amount)))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> execute(schema, "lightningOutput", List.of(tier, amount)))
                        .isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(() -> execute(schema, "lightningOutputChance", List.of(tier, amount, 0.5D)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThat(builder.createObject().runtimeRequirements()).isEmpty();
        assertThat(schema.json).isEqualTo(unchanged);
    }

    private static KubeRecipe schemaRecipe() {
        var recipe = new KubeRecipe();
        recipe.json = new JsonObject();
        recipe.json.addProperty("recipe_pool", "mmcr:test_cube");
        recipe.json.addProperty("tick_time", 40);
        return recipe;
    }

    private static void execute(KubeRecipe recipe, String function, List<Object> arguments) {
        MachineRecipeSchema.SCHEMA.functions.get(function).function()
                .execute(new TestRecipeContext(recipe), arguments);
    }

    /** @author howxu <dev@howxu.cn> */
    private record TestRecipeContext(KubeRecipe recipe) implements RecipeScriptContext {
        @Override
        public ErrorStack errors() { return ErrorStack.NONE; }

        @Override
        public Context cx() { return null; }
    }

    private static JsonObject payload(String type, long amount, float chance) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("amount", amount);
        payload.addProperty("chance", chance);
        return payload;
    }
}
