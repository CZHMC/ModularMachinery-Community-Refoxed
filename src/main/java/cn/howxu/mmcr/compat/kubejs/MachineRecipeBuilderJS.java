package cn.howxu.mmcr.compat.kubejs;

import cn.howxu.mmcr.api.compat.ae2lt.LightningTier;
import cn.howxu.mmcr.compat.ae2lt.LightningRequirement;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.machine.level.MachineLevelRegistry;
import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import cn.howxu.mmcr.api.compat.mekanism.ChemicalIngredient;
import cn.howxu.mmcr.api.compat.mekanism.ChemicalOutput;
import cn.howxu.mmcr.api.compat.mekanism.MekanismPortFamilies;
import cn.howxu.mmcr.api.recipe.MachineIngredient;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.CustomRecipeIo;
import cn.howxu.mmcr.api.recipe.RecipeIoDeclaration;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.recipe.requirement.LevelRequirement;
import cn.howxu.mmcr.api.recipe.requirement.SmartInterfaceRequirement;
import cn.howxu.mmcr.api.recipe.requirement.StageRequirement;
import cn.howxu.mmcr.api.recipe.MachineRecipeBuilder;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier.IOType;
import cn.howxu.mmcr.internal.registration.MachineRecipeConverter;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.MachineRecipeJson;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.component.DataComponentPredicateSet;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.latvian.mods.kubejs.recipe.RecipesKubeEvent;
import dev.latvian.mods.rhino.util.HideFromJS;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;

import net.minecraft.core.HolderSet;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MachineRecipeBuilderJS {
    public Identifier recipePoolId;
    public int tickTime = 40;
    public final List<MachineIngredient> inputs = new ArrayList<>();
    public final List<ItemStack> outputs = new ArrayList<>();
    private final List<Float> outputChances = new ArrayList<>();
    private final List<FluidStack> fluidOutputs = new ArrayList<>();
    private final List<RecipeModifier> conditions = new ArrayList<>();
    private int priority = 0;
    private int maxThreads = 1;
    private boolean parallelized = false;
    private boolean deriveRequirements = true;
    public long energyPerTick = 0L;
    public boolean cancelIfPerTickFails = false;
    public final Set<Identifier> requiredHostIds = new LinkedHashSet<>();
    final List<MachineRequirement> requirements = new ArrayList<>();
    final List<MachineOutput> customOutputs = new ArrayList<>();
    private boolean allowPartialOutputs = false;

    private Identifier id;
    private final List<ComponentOutput> componentOutputs = new ArrayList<>();

    public MachineRecipeBuilderJS(String id) {
        this(Identifier.parse(id));
    }

    public MachineRecipeBuilderJS(Identifier id) {
        this.id = id;
    }

    public MachineRecipeBuilderJS id(String id) {
        return id(Identifier.parse(id));
    }

    public MachineRecipeBuilderJS id(Identifier id) {
        this.id = id;
        return this;
    }

    public MachineRecipeBuilderJS recipePool(String id) {
        return recipePool(Identifier.parse(id));
    }

    public MachineRecipeBuilderJS recipePool(Identifier id) {
        if (!MachineRegistry.containsRecipePool(id)) {
            throw new IllegalArgumentException("Recipe pool not found: " + id);
        }

        this.recipePoolId = id;

        return this;
    }

    public MachineRecipeBuilderJS tickTime(int tickTime) {
        this.tickTime = tickTime;
        return this;
    }

    public MachineRecipeBuilderJS inputs(List<MachineIngredient> inputs) {
        this.inputs.clear();
        this.inputs.addAll(inputs);
        return this;
    }

    public MachineRecipeBuilderJS addInput(MachineIngredient input) {
        inputs.add(input);
        return this;
    }

    public MachineRecipeBuilderJS outputs(List<ItemStack> outputs) {
        this.outputs.clear();
        outputChances.clear();
        componentOutputs.clear();
        for (ItemStack output : outputs) addOutput(output, 1F);
        return this;
    }

    public MachineRecipeBuilderJS addOutput(ItemStack output, float chance) {
        outputs.add(output);
        outputChances.add(chance);
        return this;
    }

    public MachineRecipeBuilderJS fluidOutputs(List<FluidStack> fluidOutputs) {
        this.fluidOutputs.clear();
        this.fluidOutputs.addAll(fluidOutputs);
        return this;
    }

    public MachineRecipeBuilderJS requirements(List<MachineRequirement> requirements) {
        this.requirements.clear();
        this.requirements.addAll(requirements);
        return this;
    }

    public MachineRecipeBuilderJS addRequirement(RecipeIoDeclaration source) {
        if (source instanceof MachineRequirement requirement) {
            requirements.add(requirement);
            return this;
        }
        if (!(source instanceof CustomRecipeIo io)) {
            throw new IllegalArgumentException("Unsupported recipe IO declaration");
        }
        var delegate = MachineRecipeBuilder.recipe(id).recipePool(id);
        var declaration = delegate.custom(io).build();
        requirements.addAll(declaration.requirements());
        declaration.customOutputs().forEach(output -> customOutputs.add(MachineRecipeConverter.toOutput(output)));
        return this;
    }

    /**
     * Adds a registered codec-backed requirement or output to this recipe.
     *
     * @param typeId registered type identifier
     * @param io recipe IO direction
     * @param payload registered codec payload
     * @return this builder
     */
    public MachineRecipeBuilderJS custom(String typeId, IOType io, JsonElement payload) {
        return custom(Identifier.parse(typeId), io, payload);
    }

    public MachineRecipeBuilderJS custom(Identifier typeId, IOType io, JsonElement payload) {
        return addRequirement(new CustomRecipeIo(typeId, io, payload));
    }

    public MachineRecipeBuilderJS priority(int priority) {
        this.priority = priority;
        return this;
    }

    public MachineRecipeBuilderJS maxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        return this;
    }

    public MachineRecipeBuilderJS parallelized() {
        return parallelized(true);
    }

    public MachineRecipeBuilderJS parallelized(boolean parallelized) {
        this.parallelized = parallelized;
        return this;
    }

    public MachineRecipeBuilderJS deriveRequirements(boolean deriveRequirements) {
        this.deriveRequirements = deriveRequirements;
        return this;
    }

    public MachineRecipeBuilderJS conditions(List<MachineModifier> conditions) {
        this.conditions.clear();
        this.conditions.addAll(MachineModifier.recipeModifiers(conditions));
        return this;
    }

    public MachineRecipeBuilderJS itemInput(String itemId, long count) {
        return itemInput(Identifier.parse(itemId), count);
    }

    public MachineRecipeBuilderJS itemInput(Identifier itemId, long count) {
        return addItemInput(Ingredient.of(item(itemId)), count, DataComponentPredicateSet.EMPTY, 1F);
    }

    public MachineRecipeBuilderJS itemInput(String itemId, int count) {
        return itemInput(itemId, (long) count);
    }

    public MachineRecipeBuilderJS itemInput(Identifier itemId, int count) {
        return itemInput(itemId, (long) count);
    }

    public MachineRecipeBuilderJS tagInput(String tagId, long count) {
        return tagInput(Identifier.parse(tagId), count);
    }

    public MachineRecipeBuilderJS tagInput(Identifier tagId, long count) {
        return addItemInput(Ingredient.of(tagItems(tagId)), count,
                DataComponentPredicateSet.EMPTY, 1F);
    }

    public MachineRecipeBuilderJS tagInput(String tagId, int count) {
        return tagInput(tagId, (long) count);
    }

    public MachineRecipeBuilderJS tagInput(Identifier tagId, int count) {
        return tagInput(tagId, (long) count);
    }

    public MachineRecipeBuilderJS itemInputWithComponents(String itemId, long count, JsonElement components) {
        return itemInputWithComponents(Identifier.parse(itemId), count, components);
    }

    public MachineRecipeBuilderJS itemInputWithComponents(Identifier itemId, long count, JsonElement components) {
        return itemInputWithComponents(itemId, count, components, 1F);
    }

    public MachineRecipeBuilderJS itemInputWithComponents(String itemId, int count, JsonElement components) {
        return itemInputWithComponents(itemId, (long) count, components);
    }

    public MachineRecipeBuilderJS itemInputWithComponents(Identifier itemId, int count, JsonElement components) {
        return itemInputWithComponents(itemId, (long) count, components);
    }

    public MachineRecipeBuilderJS itemInputWithComponents(String itemId, long count, JsonElement components, float consumeChance) {
        return itemInputWithComponents(Identifier.parse(itemId), count, components, consumeChance);
    }

    public MachineRecipeBuilderJS itemInputWithComponents(Identifier itemId, long count, JsonElement components, float consumeChance) {
        return addItemInput(Ingredient.of(item(itemId)), count, componentPredicates(components), consumeChance);
    }

    public MachineRecipeBuilderJS itemInputWithComponents(String itemId, int count, JsonElement components,
                                                            float consumeChance) {
        return itemInputWithComponents(itemId, (long) count, components, consumeChance);
    }

    public MachineRecipeBuilderJS itemInputWithComponents(Identifier itemId, int count, JsonElement components,
                                                            float consumeChance) {
        return itemInputWithComponents(itemId, (long) count, components, consumeChance);
    }

    public MachineRecipeBuilderJS tagInputWithComponents(String tagId, long count, JsonElement components, float consumeChance) {
        return tagInputWithComponents(Identifier.parse(tagId), count, components, consumeChance);
    }

    public MachineRecipeBuilderJS tagInputWithComponents(Identifier tagId, long count, JsonElement components, float consumeChance) {
        return addItemInput(Ingredient.of(tagItems(tagId)), count, componentPredicates(components), consumeChance);
    }

    public MachineRecipeBuilderJS tagInputWithComponents(String tagId, int count, JsonElement components,
                                                           float consumeChance) {
        return tagInputWithComponents(tagId, (long) count, components, consumeChance);
    }

    public MachineRecipeBuilderJS tagInputWithComponents(Identifier tagId, int count, JsonElement components,
                                                           float consumeChance) {
        return tagInputWithComponents(tagId, (long) count, components, consumeChance);
    }

    public MachineRecipeBuilderJS notConsumableItemInput(String itemId, long count) {
        return notConsumableItemInput(Identifier.parse(itemId), count);
    }

    public MachineRecipeBuilderJS notConsumableItemInput(Identifier itemId, long count) {
        return addItemInput(Ingredient.of(item(itemId)), count, DataComponentPredicateSet.EMPTY, 0F);
    }

    public MachineRecipeBuilderJS notConsumableItemInput(String itemId, int count) {
        return notConsumableItemInput(itemId, (long) count);
    }

    public MachineRecipeBuilderJS notConsumableItemInput(Identifier itemId, int count) {
        return notConsumableItemInput(itemId, (long) count);
    }

    public MachineRecipeBuilderJS chancedItemInput(String itemId, long count, float consumeChance) {
        return chancedItemInput(Identifier.parse(itemId), count, consumeChance);
    }

    public MachineRecipeBuilderJS chancedItemInput(Identifier itemId, long count, float consumeChance) {
        return addItemInput(Ingredient.of(item(itemId)), count, DataComponentPredicateSet.EMPTY, consumeChance);
    }

    public MachineRecipeBuilderJS chancedItemInput(String itemId, int count, float consumeChance) {
        return chancedItemInput(itemId, (long) count, consumeChance);
    }

    public MachineRecipeBuilderJS chancedItemInput(Identifier itemId, int count, float consumeChance) {
        return chancedItemInput(itemId, (long) count, consumeChance);
    }

    /**
     * Adds a fluid input resolved from a namespaced identifier.
     *
     * @param fluidId fluid identifier (e.g. {@code minecraft:water})
     * @param amount required fluid amount in millibuckets
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS fluidInput(String fluidId, long amount) {
        return fluidInput(Identifier.parse(fluidId), amount);
    }

    public MachineRecipeBuilderJS fluidInput(Identifier fluidId, long amount) {
        return addFluidInput(fluidId, amount, 1F);
    }

    public MachineRecipeBuilderJS fluidInput(String fluidId, int amount) {
        return fluidInput(fluidId, (long) amount);
    }

    public MachineRecipeBuilderJS fluidInput(Identifier fluidId, int amount) {
        return fluidInput(fluidId, (long) amount);
    }

    /**
     * Adds a fluid input resolved from a namespaced identifier with an explicit consume chance.
     *
     * @param fluidId fluid identifier (e.g. {@code minecraft:water})
     * @param amount required fluid amount in millibuckets
     * @param consumeChance consume chance in {@code [0, 1]}; {@code 0} leaves the input required but never consumed
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS fluidInput(String fluidId, long amount, double consumeChance) {
        return fluidInput(Identifier.parse(fluidId), amount, consumeChance);
    }

    public MachineRecipeBuilderJS fluidInput(Identifier fluidId, long amount, double consumeChance) {
        return addFluidInput(fluidId, amount, (float) consumeChance);
    }

    public MachineRecipeBuilderJS fluidInput(String fluidId, int amount, double consumeChance) {
        return fluidInput(fluidId, (long) amount, consumeChance);
    }

    public MachineRecipeBuilderJS fluidInput(Identifier fluidId, int amount, double consumeChance) {
        return fluidInput(fluidId, (long) amount, consumeChance);
    }

    private MachineRecipeBuilderJS addFluidInput(Identifier fluidId, long amount, float consumeChance) {
        inputs.add(new MachineIngredient.FluidIngredient(FluidIngredient.of(fluid(fluidId)), MachineOutput.recipeStackAmount(amount), consumeChance));
        return this;
    }

    private Fluid fluid(Identifier fluidId) {
        return BuiltInRegistries.FLUID.getValue(fluidId);
    }

    public MachineRecipeBuilderJS itemOutput(String itemId, long count) {
        return itemOutput(Identifier.parse(itemId), count);
    }

    public MachineRecipeBuilderJS itemOutput(Identifier itemId, long count) {
        outputs.add(new ItemStack(item(itemId), MachineOutput.recipeStackAmount(count)));
        outputChances.add(1F);
        return this;
    }

    public MachineRecipeBuilderJS itemOutput(String itemId, int count) {
        return itemOutput(itemId, (long) count);
    }

    public MachineRecipeBuilderJS itemOutput(Identifier itemId, int count) {
        return itemOutput(itemId, (long) count);
    }

    public MachineRecipeBuilderJS chancedItemOutput(String itemId, long count, float chance) {
        return chancedItemOutput(Identifier.parse(itemId), count, chance);
    }

    public MachineRecipeBuilderJS chancedItemOutput(Identifier itemId, long count, float chance) {
        outputs.add(new ItemStack(item(itemId), MachineOutput.recipeStackAmount(count)));
        outputChances.add(chance);
        return this;
    }

    public MachineRecipeBuilderJS chancedItemOutput(String itemId, int count, float chance) {
        return chancedItemOutput(itemId, (long) count, chance);
    }

    public MachineRecipeBuilderJS chancedItemOutput(Identifier itemId, int count, float chance) {
        return chancedItemOutput(itemId, (long) count, chance);
    }

    public MachineRecipeBuilderJS itemOutputWithComponents(String itemId, long count, JsonElement components) {
        return itemOutputWithComponents(Identifier.parse(itemId), count, components);
    }

    public MachineRecipeBuilderJS itemOutputWithComponents(Identifier itemId, long count, JsonElement components) {
        if (count < 1L) {
            throw new IllegalArgumentException("Component item output count must be positive: " + count);
        }
        JsonObject stack = new JsonObject();
        stack.addProperty("id", itemId.toString());
        stack.addProperty("count", count);
        stack.add("components", components.deepCopy());
        componentOutputs.add(new ComponentOutput(outputs.size(), stack));
        return this;
    }

    public MachineRecipeBuilderJS itemOutputWithComponents(String itemId, int count, JsonElement components) {
        return itemOutputWithComponents(itemId, (long) count, components);
    }

    public MachineRecipeBuilderJS itemOutputWithComponents(Identifier itemId, int count, JsonElement components) {
        return itemOutputWithComponents(itemId, (long) count, components);
    }

    /**
     * Adds a chemical input requirement resolved from a namespaced identifier.
     *
     * @param chemicalId chemical identifier (e.g. {@code mekanism:oxygen})
     * @param amount required chemical amount
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS chemicalInput(String chemicalId, long amount) {
        return chemicalInput(requireChemicalId(chemicalId, "chemicalId"), amount);
    }

    public MachineRecipeBuilderJS chemicalInput(Identifier chemicalId, long amount) {
        return custom(MekanismPortFamilies.CHEMICAL.toString(), IOType.INPUT,
                MachineRecipeBuilder.chemicalInputPayload(ChemicalIngredient.chemical(chemicalId, amount)));
    }

    /**
     * Adds a chemical input requirement resolved from a namespaced identifier with an explicit consume chance.
     *
     * @param chemicalId chemical identifier (e.g. {@code mekanism:oxygen})
     * @param amount required chemical amount
     * @param consumeChance consume chance in {@code [0, 1]}
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS chemicalInput(String chemicalId, long amount, double consumeChance) {
        return chemicalInput(requireChemicalId(chemicalId, "chemicalId"), amount, consumeChance);
    }

    public MachineRecipeBuilderJS chemicalInput(Identifier chemicalId, long amount, double consumeChance) {
        return custom(MekanismPortFamilies.CHEMICAL.toString(), IOType.INPUT,
                MachineRecipeBuilder.chemicalInputPayload(ChemicalIngredient.chemical(chemicalId, amount), (float) consumeChance));
    }

    /**
     * Adds a chemical tag input requirement resolved from a namespaced identifier.
     *
     * @param tagId chemical tag identifier (e.g. {@code mekanism:fuels})
     * @param amount required chemical amount per matching chemical
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS chemicalTagInput(String tagId, long amount) {
        return chemicalTagInput(requireChemicalId(tagId, "tagId"), amount);
    }

    public MachineRecipeBuilderJS chemicalTagInput(Identifier tagId, long amount) {
        return custom(MekanismPortFamilies.CHEMICAL.toString(), IOType.INPUT,
                MachineRecipeBuilder.chemicalInputPayload(ChemicalIngredient.tag(tagId, amount)));
    }

    /**
     * Adds a chemical tag input requirement resolved from a namespaced identifier with an explicit consume chance.
     *
     * @param tagId chemical tag identifier (e.g. {@code mekanism:fuels})
     * @param amount required chemical amount per matching chemical
     * @param consumeChance consume chance in {@code [0, 1]}
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS chemicalTagInput(String tagId, long amount, double consumeChance) {
        return chemicalTagInput(requireChemicalId(tagId, "tagId"), amount, consumeChance);
    }

    public MachineRecipeBuilderJS chemicalTagInput(Identifier tagId, long amount, double consumeChance) {
        return custom(MekanismPortFamilies.CHEMICAL.toString(), IOType.INPUT,
                MachineRecipeBuilder.chemicalInputPayload(ChemicalIngredient.tag(tagId, amount), (float) consumeChance));
    }

    /**
     * Adds a chemical output registered through the public chemical output codec.
     *
     * @param chemicalId chemical identifier (e.g. {@code mekanism:hydrogen})
     * @param amount produced chemical amount
     * @param chance production chance between 0 and 1 inclusive
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS chemicalOutput(String chemicalId, long amount, double chance) {
        return chemicalOutput(requireChemicalId(chemicalId, "chemicalId"), amount, chance);
    }

    public MachineRecipeBuilderJS chemicalOutput(Identifier chemicalId, long amount, double chance) {
        return custom(MekanismPortFamilies.CHEMICAL.toString(), IOType.OUTPUT,
                MachineRecipeBuilder.chemicalOutputPayload(ChemicalOutput.of(chemicalId, amount, (float) chance)));
    }

    /**
     * Adds a minimum temperature input requirement through the public heat requirement codec.
     *
     * @param temperature minimum required temperature in Kelvin
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS heatTemperatureInput(double temperature) {
        return custom(MekanismPortFamilies.HEAT_TEMPERATURE.toString(), IOType.INPUT,
                MachineRecipeBuilder.heatInputPayload(temperature));
    }

    /**
     * Adds a heat output declaration through the public heat requirement codec.
     *
     * @param heat produced heat value in Kelvin per tick
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS heatOutput(double heat) {
        return custom(MekanismPortFamilies.HEAT.toString(), IOType.OUTPUT,
                MachineRecipeBuilder.heatOutputPayload(heat));
    }

    /** Consumes lightning once per parallel recipe using an AE2LT resource ID. */
    public MachineRecipeBuilderJS lightningInput(String tierId, long amount) {
        return lightningInput(Identifier.parse(tierId), amount);
    }

    public MachineRecipeBuilderJS lightningInput(Identifier tierId, long amount) {
        return addRequirement(LightningRequirement.input(LightningTier.fromId(tierId), amount));
    }

    /** Produces lightning with guaranteed output using an AE2LT resource ID. */
    public MachineRecipeBuilderJS lightningOutput(String tierId, long amount) {
        return lightningOutput(tierId, amount, 1D);
    }

    public MachineRecipeBuilderJS lightningOutput(Identifier tierId, long amount) {
        return lightningOutput(tierId, amount, 1D);
    }

    /** Produces lightning with the canonical output chance and amount clamping. */
    public MachineRecipeBuilderJS lightningOutput(String tierId, long amount, double chance) {
        return lightningOutput(Identifier.parse(tierId), amount, chance);
    }

    public MachineRecipeBuilderJS lightningOutput(Identifier tierId, long amount, double chance) {
        return addRequirement(LightningRequirement.output(LightningTier.fromId(tierId),
                amount, (float) chance));
    }

    /** Named chance variant shared with the standard KubeJS recipe schema. */
    public MachineRecipeBuilderJS lightningOutputChance(String tierId, long amount, double chance) {
        return lightningOutput(tierId, amount, chance);
    }

    public MachineRecipeBuilderJS lightningOutputChance(Identifier tierId, long amount, double chance) {
        return lightningOutput(tierId, amount, chance);
    }

    private static Identifier requireChemicalId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        try {
            return Identifier.parse(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value, exception);
        }
    }

    public MachineRecipeBuilderJS energyPerTick(long energyPerTick) {
        this.energyPerTick = energyPerTick;
        return this;
    }

    /**
     * Adds an energy input requirement measured in FE per tick.
     *
     * @param fePerTick required energy rate in FE/t
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS iFEt(long fePerTick) {
        inputs.add(new MachineIngredient.EnergyIngredient(RecipeModifier.IOType.INPUT, fePerTick));
        return this;
    }

    /**
     * Adds an energy output requirement measured in FE per tick.
     *
     * @param fePerTick produced energy rate in FE/t
     * @return this builder
     * @author howxu <dev@howxu.cn>
     */
    public MachineRecipeBuilderJS oFEt(long fePerTick) {
        inputs.add(new MachineIngredient.EnergyIngredient(RecipeModifier.IOType.OUTPUT, fePerTick));
        return this;
    }

    public MachineRecipeBuilderJS cancelIfPerTickFails(boolean cancelIfPerTickFails) {
        this.cancelIfPerTickFails = cancelIfPerTickFails;
        return this;
    }

    public MachineRecipeBuilderJS allowPartialOutputs() {
        this.allowPartialOutputs = true;
        return this;
    }

    public MachineRecipeBuilderJS allowPartialOutputs(boolean allowPartialOutputs) {
        this.allowPartialOutputs = allowPartialOutputs;
        return this;
    }

    public MachineRecipeBuilderJS smartInterfaceInput(String type, float value) {
        requirements.add(SmartInterfaceRequirement.input(type, value));
        return this;
    }

    public MachineRecipeBuilderJS smartInterfaceInput(String type, float minValue, float maxValue) {
        requirements.add(SmartInterfaceRequirement.input(type, minValue, maxValue));
        return this;
    }

    public MachineRecipeBuilderJS smartInterfaceOutput(String type, float value) {
        requirements.add(SmartInterfaceRequirement.output(type, value));
        return this;
    }

    public MachineRecipeBuilderJS requiresLevel(String typeId, String levelId) {
        return requiresLevel(Identifier.parse(typeId), Identifier.parse(levelId));
    }

    public MachineRecipeBuilderJS requiresLevel(Identifier typeId, Identifier levelId) {
        var level = MachineLevelRegistry.getLevel(levelId);
        if (level == null) {
            throw new IllegalArgumentException("Machine level not found: " + levelId);
        }
        if (!level.typeId().equals(typeId)) {
            throw new IllegalArgumentException("Machine level " + levelId + " does not belong to type " + typeId);
        }
        requirements.add(LevelRequirement.input(typeId, level.id()));
        return this;
    }

    public MachineRecipeBuilderJS requiresStage(int minStage) {
        requirements.add(StageRequirement.input(minStage));
        return this;
    }

    public MachineRecipeBuilderJS requiredHost(String hostId) {
        return requiredHost(Identifier.parse(hostId));
    }

    public MachineRecipeBuilderJS requiredHost(Identifier hostId) {
        requiredHostIds.add(hostId);
        return this;
    }

    public MachineRecipeBuilderJS requiredHosts() {
        return this;
    }

    public MachineRecipeBuilderJS requiredHosts(String... hostIds) {
        if (hostIds == null) return this;
        for (String hostId : hostIds) {
            if (hostId != null) requiredHost(hostId);
        }
        return this;
    }

    @HideFromJS
    public MachineRecipeBuilderJS requiredHosts(Identifier... hostIds) {
        if (hostIds == null) return this;
        for (Identifier hostId : hostIds) {
            if (hostId != null) requiredHost(hostId);
        }
        return this;
    }

    private MachineRecipeBuilderJS addItemInput(Ingredient item, long count, DataComponentPredicateSet components, float consumeChance) {
        inputs.add(new MachineIngredient.ItemIngredient(item, MachineOutput.recipeStackAmount(count), components, consumeChance));
        return this;
    }

    private Item item(Identifier itemId) {
        return BuiltInRegistries.ITEM.getValue(itemId);
    }

    private DataComponentPredicateSet componentPredicates(JsonElement components) {
        return DataComponentPredicateSet.CODEC.parse(JsonOps.INSTANCE, components).getOrThrow();
    }

    private HolderSet.Named<Item> tagItems(Identifier tagId) {
        var tag = TagKey.create(Registries.ITEM, tagId);
        return BuiltInRegistries.ITEM.get(tag).orElseGet(() -> HolderSet.emptyNamed(BuiltInRegistries.ITEM, tag));
    }

    public MachineRecipe createObject() {
        if (recipePoolId == null) {
            throw new IllegalStateException("recipePool() not called");
        }
        if (tickTime < 1 || energyPerTick < 0 || maxThreads < 0) {
            throw new IllegalArgumentException("Recipe tick time must be >= 1 and counts must not be negative");
        }
        for (MachineIngredient input : inputs) {
            if ((input instanceof MachineIngredient.ItemIngredient item && item.count() < 0)
                    || (input instanceof MachineIngredient.FluidIngredient fluid && fluid.amount() < 0)
                    || (input instanceof MachineIngredient.EnergyIngredient energy && energy.fePerTick() < 0)) {
                throw new IllegalArgumentException("Recipe counts must not be negative");
            }
        }
        for (ItemStack output : outputs) {
            if (output.getCount() < 0) throw new IllegalArgumentException("Item output count must not be negative");
        }
        for (FluidStack output : fluidOutputs) {
            if (output.getAmount() < 0) throw new IllegalArgumentException("Fluid output amount must not be negative");
        }

        var recipeInputs = new ArrayList<>(inputs);

        if (energyPerTick > 0) {
            recipeInputs.add(new MachineIngredient.EnergyIngredient(energyPerTick));
        }

        var recipeOutputs = new ArrayList<>(outputs);
        var recipeOutputChances = new ArrayList<>(outputChances);

        if (!componentOutputs.isEmpty()) {
            if (!RecipesKubeEvent.INSTANCE.isBound()) {
                throw new IllegalStateException("Component item outputs must be built during the KubeJS recipe event");
            }

            var ops = RecipesKubeEvent.INSTANCE.get().ops.json();
            for (int index = 0; index < componentOutputs.size(); index++) {
                var output = componentOutputs.get(index);
                recipeOutputs.add(output.index() + index, MachineOutput.RECIPE_ITEM_STACK_CODEC.parse(ops, output.stack()).getOrThrow());
                recipeOutputChances.add(output.index() + index, 1F);
            }
        }

        List<MachineRequirement> recipeRequirements = deriveRequirements || !requirements.isEmpty()
                ? new ArrayList<>()
                : null;
        if (deriveRequirements) {
            for (MachineIngredient input : recipeInputs) recipeRequirements.add(MachineRequirement.fromInput(input));
            for (int index = 0; index < recipeOutputs.size(); index++) {
                recipeRequirements.add(MachineRequirement.itemOutput(recipeOutputs.get(index), recipeOutputChances.get(index)));
            }
            for (FluidStack fluidOutput : fluidOutputs) recipeRequirements.add(MachineRequirement.fluidOutput(fluidOutput));
        }
        if (recipeRequirements != null) {
            recipeRequirements.addAll(requirements);
        }

        List<MachineOutput> canonicalOutputs = new ArrayList<>(recipeOutputs.size() + fluidOutputs.size());
        for (int index = 0; index < recipeOutputs.size(); index++) {
            canonicalOutputs.add(new MachineOutput.ItemOutput(recipeOutputs.get(index), recipeOutputChances.get(index)));
        }
        for (FluidStack fluidOutput : fluidOutputs) {
            canonicalOutputs.add(new MachineOutput.FluidOutput(fluidOutput, 1F));
        }
        MachineRecipe recipe = MachineRecipe.fromCanonical(id, recipePoolId, tickTime,
                recipeRequirements == null ? List.of() : List.copyOf(recipeRequirements), canonicalOutputs,
                List.copyOf(conditions), priority, maxThreads, cancelIfPerTickFails, parallelized,
                allowPartialOutputs, new LinkedHashSet<>(requiredHostIds));
        return MachineRecipe.withAdditionalOutputs(recipe, customOutputs);
    }

    public void build() {
        var recipe = createObject();
        var transaction = KubeJSContentReloadTransaction.active();
        if (transaction != null) {
            transaction.registerRecipe(recipe);
        } else {
            RecipeRegistry.registerStatic(recipe);
        }
    }

    private record ComponentOutput(int index, JsonObject stack) {
    }
}
