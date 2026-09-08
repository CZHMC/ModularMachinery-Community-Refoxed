package cn.howxu.mmcr.compat.mekanism.loaded;

import cn.howxu.mmcr.api.capability.plan.PlanningContext;
import cn.howxu.mmcr.api.capability.plan.RequirementPlan;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.StatusSeverity;
import cn.howxu.mmcr.api.compat.mekanism.ChemicalIngredient;
import cn.howxu.mmcr.api.compat.mekanism.MekanismFailureReasons;
import cn.howxu.mmcr.api.recipe.RecipeSyncCodec;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandler;
import cn.howxu.mmcr.api.recipe.requirement.RequirementType;
import cn.howxu.mmcr.compat.mekanism.MekanismRecipeTypes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Internal chemical requirement payload shared by the optional bridge handlers.
 *
 * @author howxu <dev@howxu.cn>
 */
public record LoadedChemicalRequirement(RecipeModifier.IOType io, ChemicalIngredient ingredient,
                                        float chance, List<String> tags) implements MachineRequirement {
    private static final RequirementHandler<LoadedChemicalRequirement> UNAVAILABLE =
            (requirement, capabilities, context) -> unavailable(requirement, context);
    private static volatile RequirementHandler<LoadedChemicalRequirement> delegate = UNAVAILABLE;
    private static final RequirementHandler<LoadedChemicalRequirement> DELEGATING_HANDLER =
            (requirement, capabilities, context) -> delegate.plan(requirement, capabilities, context);

    public static final MapCodec<LoadedChemicalRequirement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("type").forGetter(ignored -> MekanismRecipeTypes.CHEMICAL.toString()),
            RecipeModifier.IO_TYPE_CODEC.optionalFieldOf("io", RecipeModifier.IOType.INPUT)
                    .forGetter(LoadedChemicalRequirement::io),
            Codec.STRING.optionalFieldOf("kind", "chemical")
                    .forGetter(value -> value.ingredient().kind().name().toLowerCase(Locale.ROOT)),
            Identifier.CODEC.fieldOf("id").forGetter(value -> value.ingredient().id()),
            Codec.LONG.fieldOf("amount").forGetter(value -> value.ingredient().amount()),
            Codec.FLOAT.optionalFieldOf("chance", 1F).forGetter(LoadedChemicalRequirement::chance),
            Codec.STRING.listOf().optionalFieldOf("tags", List.of()).forGetter(LoadedChemicalRequirement::tags)
    ).apply(instance, (ignored, io, kind, id, amount, chance, tags) ->
            new LoadedChemicalRequirement(io, new ChemicalIngredient(parseKind(kind), id, amount), chance, tags)));

    public static final RequirementType<LoadedChemicalRequirement> TYPE = new RequirementType.Definition<>(
            MekanismRecipeTypes.CHEMICAL, CODEC, DELEGATING_HANDLER,
            LoadedChemicalRequirement::copy, RecipeSyncCodec.json(CODEC.codec(), LoadedChemicalRequirement::validateSync));

    public LoadedChemicalRequirement {
        Objects.requireNonNull(io, "io");
        Objects.requireNonNull(ingredient, "ingredient");
        if (!Float.isFinite(chance) || chance < 0F || chance > 1F) {
            throw new IllegalArgumentException("chance must be between 0 and 1");
        }
        if (io == RecipeModifier.IOType.OUTPUT && ingredient.kind() != ChemicalIngredient.Kind.CHEMICAL) {
            throw new IllegalArgumentException("chemical outputs must name a chemical");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static LoadedChemicalRequirement input(ChemicalIngredient ingredient) {
        return new LoadedChemicalRequirement(RecipeModifier.IOType.INPUT, ingredient, 1F, List.of());
    }

    public static LoadedChemicalRequirement output(Identifier id, long amount, float chance) {
        return new LoadedChemicalRequirement(RecipeModifier.IOType.OUTPUT,
                ChemicalIngredient.chemical(id, amount), chance, List.of());
    }

    @Override
    public RequirementType<LoadedChemicalRequirement> type() {
        return TYPE;
    }

    public static void installHandler(RequirementHandler<LoadedChemicalRequirement> handler) {
        delegate = Objects.requireNonNull(handler, "handler");
    }

    public static void installUnavailableHandler() {
        delegate = UNAVAILABLE;
    }

    private static LoadedChemicalRequirement copy(LoadedChemicalRequirement requirement) {
        return new LoadedChemicalRequirement(requirement.io(), requirement.ingredient(), requirement.chance(),
                requirement.tags());
    }

    private static void validateSync(LoadedChemicalRequirement requirement) {
        if (requirement.ingredient().amount() > 10_000_000_000L) {
            throw new IllegalArgumentException("Invalid chemical amount: " + requirement.ingredient().amount());
        }
        if (requirement.tags().size() > 1024) throw new IllegalArgumentException("Invalid tag count");
    }

    private static ChemicalIngredient.Kind parseKind(String kind) {
        try {
            return ChemicalIngredient.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown chemical ingredient kind: " + kind, exception);
        }
    }

    private static RequirementPlan unavailable(LoadedChemicalRequirement requirement, PlanningContext context) {
        return new RequirementPlan(context.requirementIndex(), 0, List.of(), new ExecutionStatus(
                requirement.type().id(), StatusSeverity.BLOCKED,
                requirement.type().id(), Map.of("reason", MekanismFailureReasons.MEKANISM_UNAVAILABLE.id().toString())));
    }
}
