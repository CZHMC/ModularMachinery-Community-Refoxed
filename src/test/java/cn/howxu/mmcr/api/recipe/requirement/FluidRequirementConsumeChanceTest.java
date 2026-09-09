package cn.howxu.mmcr.api.recipe.requirement;

import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.test.TestBootstrap;
import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author howxu <dev@howxu.cn>
 */
class FluidRequirementConsumeChanceTest {
    private static FluidIngredient water;

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
        Fluids.WATER.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        water = FluidIngredient.of(Fluids.WATER);
    }

    @Test
    void codec_round_trips_consume_chance() {
        FluidRequirement original = newRequirement(RecipeModifier.IOType.INPUT, 0.25F);

        var encoded = FluidRequirement.CODEC.codec().encodeStart(jsonOps(), original).getOrThrow();
        var decoded = FluidRequirement.CODEC.codec().parse(jsonOps(), encoded).getOrThrow();

        assertThat(decoded.consumeChance()).isEqualTo(0.25F);
    }

    @Test
    void codec_defaults_consume_chance_to_one_when_missing() {
        FluidRequirement original = new FluidRequirement(RecipeModifier.IOType.INPUT, water, 1000,
                FluidStack.EMPTY, 1F, of());

        var encoded = FluidRequirement.CODEC.codec().encodeStart(jsonOps(), original).getOrThrow();
        var json = encoded.getAsJsonObject();
        json.remove("consume_chance");

        var decoded = FluidRequirement.CODEC.codec().parse(jsonOps(), json).getOrThrow();
        assertThat(decoded.consumeChance()).isEqualTo(1F);
    }

    @Test
    void copy_preserves_consume_chance() {
        FluidRequirement original = newRequirement(RecipeModifier.IOType.INPUT, 0.5F);
        assertThat(FluidRequirement.copyForTest(original).consumeChance()).isEqualTo(0.5F);
    }

    private static FluidRequirement newRequirement(RecipeModifier.IOType io, float consumeChance) {
        return new FluidRequirement(io, water, 1000,
                FluidStack.EMPTY, 1F, of(), consumeChance);
    }

    // Helper to keep List.of(...) call sites tidy.
    private static <T> List<T> of(T... values) { return List.of(values); }

    private static DynamicOps<JsonElement> jsonOps() {
        return RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }
}
