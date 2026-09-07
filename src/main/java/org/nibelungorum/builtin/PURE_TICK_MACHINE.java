package org.nibelungorum.builtin;

import cn.howxu.mmcr.api.publicapi.controller.ControllerRuntimeContext;
import cn.howxu.mmcr.api.publicapi.controller.ControllerScreenTextRegistry;
import cn.howxu.mmcr.api.publicapi.controller.ControllerScreenTextScope;
import cn.howxu.mmcr.api.publicapi.event.MMCRMachineDefinationsEvent;
import cn.howxu.mmcr.api.publicapi.event.MMCRMachineStructuresEvent;
import cn.howxu.mmcr.api.publicapi.machine.InterfacePredicates;
import cn.howxu.mmcr.api.publicapi.machine.MachineBuilder;
import cn.howxu.mmcr.api.publicapi.machine.MachineStructureBuilder;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.EnergyRequirement;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import static cn.howxu.mmcr.api.publicapi.machine.BlockPredicate.any;
import static cn.howxu.mmcr.api.publicapi.machine.BlockPredicate.block;
import static cn.howxu.mmcr.internal.registration.BuiltinRegistration.id;

/**
 * @author howxu <dev@howxu.cn>
 */
@EventBusSubscriber
public class PURE_TICK_MACHINE {

    private static final Identifier PURE_TICK_MACHINE = id("pure_tick_machine");
    private static final Identifier FE_STATUS = id("fe_status");
    private static final Identifier PURE_TICK_STATUS = id("pure_tick_status");

    public static void registerDefinitions(MMCRMachineDefinationsEvent event) {
        ControllerScreenTextRegistry.register(PURE_TICK_MACHINE, context -> {
            context.screenText().append(
                    ControllerScreenTextScope.CONTROLLER,
                    FE_STATUS,
                    Component.literal("FE is needed!"));
            context.screenText().append(
                    ControllerScreenTextScope.CONTROLLER,
                    PURE_TICK_STATUS,
                    Component.literal("No Ingot input"));
        });

        if (!event.definitions().containsKey(PURE_TICK_MACHINE)) {
            var machine = MachineBuilder
                    .machine(PURE_TICK_MACHINE)
                    .displayNameKey("machine.mmcr.pure_tick_machine")
                    .appearance(a -> a.machineBasicBlock(Identifier.parse("minecraft:green_terracotta")))
                    .allowMultithreading()
                    .maxParallelism(Integer.MAX_VALUE)
                    .tickBehavior(behavior -> behavior.serverTick(context -> {
                        if (!context.isDue(40)) return;

                        var planFe = context.ioPlan();
                        planFe.addInput(new EnergyRequirement(RecipeModifier.IOType.INPUT, 10));
                        var feSimulation = planFe.simulate();

                        if (!feSimulation.energySatisfied()) {
                            context.screenText().replace(FE_STATUS,
                                    Component.literal("FE is needed!"));
                            return;
                        }

                        if (!planFe.commit().successful()) {
                            context.screenText().replace(FE_STATUS,
                                    Component.literal("FE consume error!"));
                            return;
                        }

                        context.screenText().replace(FE_STATUS,
                                Component.literal("Machine do a run!"));

                        var level = context.level();
                        var pos = context.controllerPos();
                        var area = new AABB(
                                pos.getX() - 1,
                                level.getMinY(),
                                pos.getZ() - 1,
                                pos.getX() + 2,
                                level.getMaxY() + 1,
                                pos.getZ() + 2);
                        var players = level.getEntitiesOfClass(Player.class, area);
                        for (var player : players) {
                            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.EVENT);
                            if (bolt != null) {
                                bolt.setPos(player.getX(), player.getY(), player.getZ());
                                bolt.setVisualOnly(false);
                                level.addFreshEntity(bolt);
                            }
                        }

                        var plan = context.ioPlan();
                        plan.addInput(new ItemRequirement(
                                RecipeModifier.IOType.INPUT,
                                Ingredient.of(Items.IRON_INGOT),
                                1,
                                net.minecraft.world.item.ItemStack.EMPTY));
                        plan.add(new ItemRequirement(
                                RecipeModifier.IOType.OUTPUT,
                                null,
                                0,
                                new net.minecraft.world.item.ItemStack(Items.GOLD_NUGGET, 1),
                                1.0F,
                                java.util.List.of()));

                        var simulation = plan.simulate();

                        if (!simulation.inputsSatisfied()) return;
                        boolean outputAvailable = true;

                        for (var output : simulation.outputs()) {
                            if (output.accepted() < output.requested()) {
                                outputAvailable = false;
                            }
                        }

                        if (!outputAvailable) return;

                        context.screenText().replace(PURE_TICK_STATUS,
                                Component.literal("Iron Ingot inputed"));

                        plan.commit();
                    }))
                    .build();
            event.registerMachine(machine);
        }
    }

    @SubscribeEvent
    public static void registerStructures(MMCRMachineStructuresEvent event) {
        if (!event.structures().containsKey(PURE_TICK_MACHINE)) {
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
                                            InterfacePredicates.factoryController(),
                                            block(Blocks.GREEN_WOOL)
                                    ))
                                    .controller('C')
                            )
                    )
                    .build(PURE_TICK_MACHINE);
            event.registerStructure(structure);
        }
    }
}