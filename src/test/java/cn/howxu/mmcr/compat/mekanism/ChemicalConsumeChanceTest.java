package cn.howxu.mmcr.compat.mekanism;

import cn.howxu.mmcr.api.compat.mekanism.ChemicalIngredient;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedChemicalRequirement;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChemicalConsumeChanceTest {
    @Test
    void loaded_chemical_codec_round_trips_consume_chance() {
        LoadedChemicalRequirement original = new LoadedChemicalRequirement(RecipeModifier.IOType.INPUT,
                ChemicalIngredient.chemical(Identifier.parse("mekanism:oxygen"), 1_000L), 1F, List.of(), 0.25F);

        var encoded = LoadedChemicalRequirement.CODEC.codec().encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        var decoded = LoadedChemicalRequirement.CODEC.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertThat(decoded.consumeChance()).isEqualTo(0.25F);
    }

    @Test
    void loaded_chemical_codec_defaults_consume_chance_when_missing() {
        LoadedChemicalRequirement original = LoadedChemicalRequirement.input(
                ChemicalIngredient.chemical(Identifier.parse("mekanism:oxygen"), 1_000L));

        var encoded = LoadedChemicalRequirement.CODEC.codec().encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        var json = encoded.getAsJsonObject();
        json.remove("consume_chance");

        var decoded = LoadedChemicalRequirement.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow();
        assertThat(decoded.consumeChance()).isEqualTo(1F);
    }

    @Test
    void unavailable_chemical_codec_round_trips_consume_chance() {
        var encoded = MekanismRecipeDeclarations.CHEMICAL_CODEC.codec()
                .encodeStart(JsonOps.INSTANCE,
                        new MekanismRecipeDeclarations.UnavailableChemicalRequirement(RecipeModifier.IOType.INPUT,
                                ChemicalIngredient.chemical(Identifier.parse("mekanism:oxygen"), 1_000L), 1F, List.of(), 0.5F))
                .getOrThrow();
        var decoded = MekanismRecipeDeclarations.CHEMICAL_CODEC.codec().parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertThat(decoded.consumeChance()).isEqualTo(0.5F);
    }
}
