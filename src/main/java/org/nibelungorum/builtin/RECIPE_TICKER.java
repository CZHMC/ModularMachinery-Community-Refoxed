package org.nibelungorum.builtin;

import cn.howxu.mmcr.api.publicapi.controller.ControllerScreenTextRegistry;
import cn.howxu.mmcr.api.publicapi.controller.ControllerScreenTextScope;
import cn.howxu.mmcr.api.publicapi.event.MMCRMachineDefinationsEvent;
import cn.howxu.mmcr.api.publicapi.event.MMCRMachineRecipesEvent;
import cn.howxu.mmcr.api.publicapi.event.MMCRMachineStructuresEvent;
import cn.howxu.mmcr.api.publicapi.machine.InterfacePredicates;
import cn.howxu.mmcr.api.publicapi.machine.MachineBuilder;
import cn.howxu.mmcr.api.publicapi.machine.MachineStructureBuilder;
import cn.howxu.mmcr.api.publicapi.recipe.MachineRecipeBuilder;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.ArrayList;

import static cn.howxu.mmcr.api.publicapi.machine.BlockPredicate.any;
import static cn.howxu.mmcr.api.publicapi.machine.BlockPredicate.block;
import static cn.howxu.mmcr.internal.registration.BuiltinRegistration.id;

/**
 * @author howxu <dev@howxu.cn>
 */
@EventBusSubscriber
public class RECIPE_TICKER {

    private static final Identifier RECIPE_TICKER = id("recipe_ticker");
    private static final Identifier BEFORE_LINE = id("before_line");
    private static final Identifier IN_LINE = id("in_line");
    private static final Identifier AFTER_LINE = id("after_line");
    private static final Identifier DISPLAY_WHEN_IDLE = id("display_when_idle");
    private static final Identifier DISPLAY_WHEN_IDLE_EMPTY_LINE = id("display_when_idle_empty_line");
    private static final Identifier DISPLAY_WHEN_START_RECIPE = id("display_when_start_recipe");

    public static void registerDefinitions(MMCRMachineDefinationsEvent event) {
        ControllerScreenTextRegistry.register(RECIPE_TICKER, context -> {
            context.screenText().append(
                    ControllerScreenTextScope.CONTROLLER,
                    BEFORE_LINE,
                    Component.translatable("gui.mmcr.before_line"));
            context.screenText().appendAfter(
                    ControllerScreenTextScope.CONTROLLER,
                    IN_LINE,
                    id("sp_line_1"),
                    Component.translatable("gui.mmcr.in_line"));
            context.screenText().append(
                    ControllerScreenTextScope.CONTROLLER,
                    AFTER_LINE,
                    Component.translatable("gui.mmcr.after_line"));
        });

        if (!event.definitions().containsKey(RECIPE_TICKER)) {
            var machine = MachineBuilder
                    .machine(RECIPE_TICKER)
                    .displayNameKey("machine.mmcr.recipe_ticker")
                    .appearance(a -> a.machineBasicBlock(Identifier.parse("minecraft:green_terracotta")))
                    .recipeBehavior(behavior -> behavior
                            .idleStart(ctx -> {
                                var screen = ctx.screenText();
                                screen.append(
                                        ControllerScreenTextScope.OPERATION,
                                        DISPLAY_WHEN_IDLE_EMPTY_LINE,
                                        Component.literal(" "));
                                screen.append(
                                        ControllerScreenTextScope.OPERATION,
                                        DISPLAY_WHEN_IDLE,
                                        Component.translatable("gui.mmcr.display_when_idle"));
                            })
                            .idleEnd(ctx -> {
                            })
                            .beforeStart(ctx -> {
                                var screen = ctx.machineContext().screenText();
                                screen.remove(ControllerScreenTextScope.OPERATION, DISPLAY_WHEN_IDLE_EMPTY_LINE);
                                screen.remove(ControllerScreenTextScope.OPERATION, DISPLAY_WHEN_IDLE);

                                var machineContext = ctx.machineContext();
                                var level = machineContext.level();
                                var controllerPos = machineContext.controllerPos();
                                var area = new AABB(
                                        controllerPos.getX() - 2,
                                        level.getMinY(),
                                        controllerPos.getZ() - 2,
                                        controllerPos.getX() + 3,
                                        level.getMaxY() + 1,
                                        controllerPos.getZ() + 3);

                                for (var entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                                    entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                                            MobEffects.STRENGTH, 10000, 1));
                                }

                                var nextRequirements = new ArrayList<cn.howxu.mmcr.api.recipe.requirement.MachineRequirement>();
                                boolean changed = false;

                                for (var requirement : ctx.requirements()) {
                                    if (!(requirement instanceof ItemRequirement itemRequirement)
                                            || requirement.io() != RecipeModifier.IOType.INPUT) {
                                        nextRequirements.add(requirement);
                                        continue;
                                    }

                                    var possibleItems = ingredientItems(itemRequirement);
                                    boolean isExactlyGold = itemRequirement.count() == 32
                                            && possibleItems.size() == 1
                                            && BuiltInRegistries.ITEM.getKey(possibleItems.get(0).value())
                                                    .toString().equals("minecraft:gold_ingot");

                                    if (isExactlyGold) {
                                        nextRequirements.add(new ItemRequirement(
                                                itemRequirement.io(),
                                                itemRequirement.item(),
                                                1,
                                                itemRequirement.stack(),
                                                itemRequirement.chance(),
                                                itemRequirement.tags(),
                                                itemRequirement.components(),
                                                itemRequirement.consumeChance()));
                                        changed = true;
                                    } else {
                                        nextRequirements.add(requirement);
                                    }
                                }

                                if (changed) {
                                    ctx.setRequirements(nextRequirements);
                                }
                            })
                            .recipeTick(ctx -> {
                                var screen = ctx.machineContext().screenText();
                                screen.appendAfter(
                                        ControllerScreenTextScope.OPERATION,
                                        DISPLAY_WHEN_START_RECIPE,
                                        id("in_line"),
                                        Component.literal("正在使用雷霆大猪咪暴力执行配方"));
                            })
                            .beforeFinish(ctx -> {
                                var machineContext = ctx.machineContext();
                                var level = machineContext.level();
                                var controllerPos = machineContext.controllerPos();
                                var area = new AABB(
                                        controllerPos.getX() - 2,
                                        level.getMinY(),
                                        controllerPos.getZ() - 2,
                                        controllerPos.getX() + 3,
                                        level.getMaxY() + 1,
                                        controllerPos.getZ() + 3);

                                for (var entity : level.getEntitiesOfClass(LivingEntity.class, area)) {
                                    entity.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                                            MobEffects.NIGHT_VISION, 10000, 1));
                                }
                            })
                    )
                    .build();
            event.registerMachine(machine);
        }
    }

    @SubscribeEvent
    public static void registerStructures(MMCRMachineStructuresEvent event) {
        if (!event.structures().containsKey(RECIPE_TICKER)) {
            var structure = MachineStructureBuilder
                    .structure()
                    .fullStructure(s -> s
                            .pattern(p -> p
                                    .layer("XXX", "AAA", "XXX")
                                    .layer("XXX", "A A", "X X")
                                    .layer("XXX", "ACA", "XXX")
                                    .where('X', block(Blocks.GREEN_TERRACOTTA))
                                    .where('A', any(
                                            InterfacePredicates.anyOfItemInput(),
                                            InterfacePredicates.anyOfItemOutput(),
                                            InterfacePredicates.anyOfEnergyInput(),
                                            InterfacePredicates.parallelControllers(),
                                            block(Blocks.GREEN_WOOL)
                                    ))
                                    .controller('C')
                            )
                    )
                    .build(RECIPE_TICKER);
            event.registerStructure(structure);
        }
    }

    @SubscribeEvent
    public static void register(MMCRMachineRecipesEvent event) {
        var recipe = MachineRecipeBuilder
                .recipe(RECIPE_TICKER.withSuffix("_recipe_1"), RECIPE_TICKER)
                .inputItem(Items.COAL, 10000)
                .inputItem(Items.DIAMOND, 8)
                .outputItem(Items.GOLD_INGOT, 9)
                .inputEnergy(20)
                .duration(500)
                .build();
        event.registerRecipe(recipe);

        recipe = MachineRecipeBuilder
                .recipe(RECIPE_TICKER.withSuffix("_recipe_2"), RECIPE_TICKER)
                .inputItem(Items.DIAMOND, 114514)
                .inputItem(Items.IRON_INGOT, 8)
                .outputItem(Items.COAL, 18)
                .inputEnergy(20)
                .duration(300)
                .build();
        event.registerRecipe(recipe);

        recipe = MachineRecipeBuilder
                .recipe(RECIPE_TICKER.withSuffix("_recipe_3"), RECIPE_TICKER)
                .inputItem(Items.GOLD_INGOT, 32)
                .inputItem(Items.STICK, 8)
                .outputItem(Items.DIAMOND, 3)
                .inputEnergy(20)
                .duration(300)
                .build();
        event.registerRecipe(recipe);
    }

    private static java.util.List<Holder<net.minecraft.world.item.Item>> ingredientItems(ItemRequirement requirement) {
        if (requirement.item() == null) return java.util.List.of();
        return requirement.item().items().toList();
    }
}