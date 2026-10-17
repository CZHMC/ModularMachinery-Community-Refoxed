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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static JsonObject payload(String type, long amount, float chance) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", type);
        payload.addProperty("amount", amount);
        payload.addProperty("chance", chance);
        return payload;
    }
}
