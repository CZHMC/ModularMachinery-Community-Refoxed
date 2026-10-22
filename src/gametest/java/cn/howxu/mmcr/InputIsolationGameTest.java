package cn.howxu.mmcr;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.api.compat.mekanism.ChemicalIngredient;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.Machine;
import cn.howxu.mmcr.api.machine.MachineControllerSpec;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.machine.PortRequirementSpec;
import cn.howxu.mmcr.api.recipe.CraftingContext;
import cn.howxu.mmcr.api.recipe.InputScope;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.component.ComponentPredicate;
import cn.howxu.mmcr.api.recipe.component.DataComponentPredicateSet;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier.IOType;
import cn.howxu.mmcr.api.recipe.requirement.EnergyRequirement;
import cn.howxu.mmcr.api.recipe.requirement.FluidRequirement;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.PatternRequestState;
import cn.howxu.mmcr.compat.mekanism.loaded.ChemicalPortBlockEntity;
import cn.howxu.mmcr.compat.mekanism.loaded.HeatPortBlockEntity;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedChemicalRequirement;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedHeatRequirement;
import cn.howxu.mmcr.internal.block.MachineControllerBlock;
import cn.howxu.mmcr.internal.capability.FluidHatchCapability;
import cn.howxu.mmcr.internal.capability.ItemBusCapability;
import cn.howxu.mmcr.internal.menu.AbstractMachineMenu;
import cn.howxu.mmcr.internal.menu.InputIsolationMenuState;
import cn.howxu.mmcr.internal.menu.ItemBusMenu;
import cn.howxu.mmcr.internal.menu.MachineControllerMenu;
import cn.howxu.mmcr.internal.multiblock.SharedIoCoordinator;
import cn.howxu.mmcr.internal.runtime.CraftingRuntime;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import cn.howxu.mmcr.internal.runtime.PatternStartReservation;
import cn.howxu.mmcr.internal.sync.RuntimeContentVersion;
import cn.howxu.mmcr.internal.tile.EnergyInputHatchBlockEntity;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.internal.tile.ItemInputBusBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerRuntime;
import cn.howxu.mmcr.registry.ModBlocks;
import com.google.gson.JsonPrimitive;
import mekanism.common.registries.MekanismChemicals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.util.ProblemReporter;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.howxu.mmcr.util.IOType.INPUT;

/** Registered storage, real menu synchronization and explicitly admitted runtime operations.
 * @author howxu <dev@howxu.cn>
 */
public final class InputIsolationGameTest {
    private InputIsolationGameTest() { }

    public static void itemSourcesCannotPoolAcrossIsolation(GameTestHelper helper) {
        try (var fixture = fixture(helper, "items", "item_input_bus", "combined_input_basic")) {
            var first = fixture.ports.get(0);
            var second = fixture.ports.get(1);
            first.setInputIsolated(true);
            second.setInputIsolated(true);
            insert(helper, first.itemStorage(), ItemResource.of(Items.IRON_INGOT), 1);
            insert(helper, second.itemStorage(), ItemResource.of(Items.IRON_INGOT), 1);
            var requirements = List.<MachineRequirement>of(item(2));
            var split = fixture.context().planRequirements(requirements, 1, Map.of());
            helper.assertFalse(split.successful(), "Separate isolated registered item sources cannot pool one ingredient each");
            helper.assertTrue(first.itemStorage().amount(0) == 1 && second.itemStorage().amount(0) == 1,
                    "Rejected item planning leaves both real inventories untouched");
            first.setInputIsolated(false);
            second.setInputIsolated(false);
            var ordinary = fixture.context().planRequirements(requirements, 1, Map.of());
            helper.assertTrue(ordinary.successful() && ordinary.plan().commit(),
                    "The same registered item sources can jointly pay an ordinary recipe");
            helper.assertTrue(first.itemStorage().amount(0) == 0 && second.itemStorage().amount(0) == 0,
                    "Ordinary commit extracts exactly the resources rejected across isolated groups");
        }
        helper.succeed();
    }

    public static void fluidSourcesCannotPoolAcrossIsolation(GameTestHelper helper) {
        try (var fixture = fixture(helper, "fluids", "fluid_input_hatch", "combined_input_basic")) {
            var first = fixture.ports.get(0);
            var second = fixture.ports.get(1);
            fixture.controller.setFullyInputIsolated(true);
            insert(helper, first.fluidStorage(), FluidResource.of(Fluids.WATER), 125);
            insert(helper, second.fluidStorage(), FluidResource.of(Fluids.WATER), 125);
            var requirements = List.<MachineRequirement>of(water(250));
            var split = fixture.context().planRequirements(requirements, 1, Map.of());
            helper.assertFalse(split.successful(), "Full isolation cannot pool water across registered hatch and combined port");
            helper.assertTrue(first.fluidStorage().amount(0) == 125 && second.fluidStorage().amount(0) == 125,
                    "Rejected fluid planning cannot partially drain either source");
            fixture.controller.setFullyInputIsolated(false);
            var ordinary = fixture.context().planRequirements(requirements, 1, Map.of());
            helper.assertTrue(ordinary.successful() && ordinary.plan().commit(),
                    "Turning full isolation off restores actual ordinary multi-source fluid payment");
            helper.assertTrue(first.fluidStorage().amount(0) == 0 && second.fluidStorage().amount(0) == 0,
                    "Successful ordinary fluid payment drains both sources once");
        }
        helper.succeed();
    }

    public static void combinedInputSharesEnergyAndOutputAtomically(GameTestHelper helper) {
        try (var fixture = fixture(helper, "combined", "combined_input_basic", "fluid_input_hatch",
                "energy_input_hatch", "item_output_bus")) {
            var combined = fixture.ports.get(0);
            var outside = fixture.ports.get(1);
            var energy = (EnergyInputHatchBlockEntity) fixture.ports.get(2);
            var output = fixture.ports.get(3);
            fixture.controller.setFullyInputIsolated(true);
            insert(helper, combined.itemStorage(), ItemResource.of(Items.IRON_INGOT), 2);
            insert(helper, outside.fluidStorage(), FluidResource.of(Fluids.WATER), 250);
            energy.getEnergyStorage().setAmount(20);
            var requirements = List.<MachineRequirement>of(item(2), water(250), new EnergyRequirement(7),
                    new ItemRequirement(IOType.OUTPUT, null, 0, new ItemStack(Items.GOLD_NUGGET, 2)));
            helper.assertFalse(fixture.context().planRequirements(requirements, 1, Map.of()).successful(),
                    "Iron in one isolated combined port cannot borrow another hatch's water");
            helper.assertTrue(combined.itemStorage().amount(0) == 2 && outside.fluidStorage().amount(0) == 250
                            && energy.getEnergyStorage().amount() == 20 && output.itemStorage().amount(0) == 0,
                    "Cross-family rejection leaves item, fluid, energy and output unchanged");
            insert(helper, combined.fluidStorage(), FluidResource.of(Fluids.WATER), 250);
            var prepared = fixture.context().planRequirements(requirements, 1, Map.of());
            helper.assertTrue(prepared.successful() && prepared.plan().inputScope().sources().equals(List.of(combined.getBlockPos())),
                    "All input facets on one registered combined port form one complete isolated source");
            energy.getEnergyStorage().setAmount(0);
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertFalse(prepared.plan().commit(transaction), "Live energy loss rejects an otherwise complete isolated commit");
                helper.assertTrue(combined.itemStorage().amount(0) == 0 && combined.fluidStorage().amount(0) == 0,
                        "Real material operations ran before the late energy failure");
            }
            helper.assertTrue(combined.itemStorage().amount(0) == 2 && combined.fluidStorage().amount(0) == 250
                            && outside.fluidStorage().amount(0) == 250 && output.itemStorage().amount(0) == 0,
                    "Root rollback restores every isolated material and leaves global output empty");
            energy.getEnergyStorage().setAmount(20);
            var retry = fixture.context().planRequirements(requirements, 1, Map.of());
            helper.assertTrue(retry.successful() && retry.plan().commit(), "Complete isolated input can use globally shared FE and output");
            helper.assertTrue(combined.itemStorage().amount(0) == 0 && combined.fluidStorage().amount(0) == 0
                            && outside.fluidStorage().amount(0) == 250 && energy.getEnergyStorage().amount() == 13
                            && output.itemStorage().resource(0).equals(ItemResource.of(Items.GOLD_NUGGET))
                            && output.itemStorage().amount(0) == 2,
                    "Successful commit pays only the chosen source, shares energy and emits the actual output");
        }
        helper.succeed();
    }

    public static void fullIsolationPreservesLocalAndIncludesReplacement(GameTestHelper helper) {
        try (var fixture = fixture(helper, "full_or_local", "combined_input_basic", "combined_input_basic")) {
            var local = fixture.ports.get(0);
            var ordinary = fixture.ports.get(1);
            local.setInputIsolated(true);
            fixture.controller.setFullyInputIsolated(true);
            helper.assertTrue(local.isInputIsolated() && !ordinary.isInputIsolated()
                            && fixture.context().inputRouting().inputPorts().stream().allMatch(port -> port.isolated()),
                    "Controller full OR local isolates all sources without rewriting the local fields");
            BlockPos replacementPos = new BlockPos(2, 1, 0);
            helper.setBlock(replacementPos, ModBlocks.CASING.get().defaultBlockState());
            helper.setBlock(replacementPos, ModBlocks.BLOCKS.get("combined_input_basic").get().defaultBlockState());
            var replacement = helper.getBlockEntity(replacementPos, IOPortBlockEntity.class);
            fixture.controller.invalidateFormedStructure();
            fixture.controller.requestImmediateStructureCheck();
            fixture.controller.serverTick();
            helper.assertTrue(fixture.controller.currentStructureSnapshot().formed() && replacement != ordinary
                            && !replacement.isInputIsolated()
                            && fixture.context().inputRouting().inputPorts().stream().allMatch(port -> port.isolated()),
                    "Freshly registered replacement joins controller full isolation with its local default still false");
            fixture.controller.setFullyInputIsolated(false);
            var routing = fixture.context().inputRouting();
            helper.assertTrue(local.isInputIsolated() && !replacement.isInputIsolated()
                            && routing.inputPorts().stream().anyMatch(port -> port.position().equals(local.getBlockPos()) && port.isolated())
                            && routing.inputPorts().stream().anyMatch(port -> port.position().equals(replacement.getBlockPos()) && !port.isolated()),
                    "Disabling full restores the retained local choice and leaves the new source ordinary");
            insert(helper, local.itemStorage(), ItemResource.of(Items.IRON_INGOT), 2);
            insert(helper, replacement.fluidStorage(), FluidResource.of(Fluids.WATER), 250);
            helper.assertFalse(fixture.context().planRequirements(List.of(item(2), water(250)), 1, Map.of()).successful(),
                    "Remaining local isolation still rejects cross-source item/fluid mixing after full is disabled");
            local.setInputIsolated(false);
            var joined = fixture.context().planRequirements(List.of(item(2), water(250)), 1, Map.of());
            helper.assertTrue(joined.successful() && joined.plan().commit(), "Disabling the local choice restores ordinary mixing");
        }
        helper.succeed();
    }

    public static void menusPersistAndSynchronizeTwoViewers(GameTestHelper helper) {
        try (var fixture = fixture(helper, "menus", "item_input_bus")) {
            Player first = helper.makeMockPlayer(GameType.SURVIVAL);
            Player second = helper.makeMockPlayer(GameType.SURVIVAL);
            var input = (ItemInputBusBlockEntity) fixture.ports.getFirst();
            first.setPos(input.getBlockPos().getCenter());
            second.setPos(input.getBlockPos().getCenter());
            checkMenus(helper, input, first, second,
                    new ItemBusMenu(11, first.getInventory(), input), new ItemBusMenu(12, second.getInventory(), input),
                    new ItemBusMenu(11, new Inventory(null, null)), new ItemBusMenu(12, new Inventory(null, null)));
            helper.assertTrue(input.isInputIsolated(), "Real port NBT restores the menu-enabled local setting");
            first.setPos(fixture.controller.getBlockPos().getCenter());
            second.setPos(fixture.controller.getBlockPos().getCenter());
            checkMenus(helper, fixture.controller, first, second,
                    new MachineControllerMenu(21, first.getInventory(), fixture.controller),
                    new MachineControllerMenu(22, second.getInventory(), fixture.controller),
                    new MachineControllerMenu(21, new Inventory(null, null)), new MachineControllerMenu(22, new Inventory(null, null)));
            helper.assertTrue(fixture.controller.isFullyInputIsolated() && input.isInputIsolated(),
                    "Controller NBT restores full independently of the port's persisted local setting");
        }
        helper.succeed();
    }

    private static void checkMenus(GameTestHelper helper, BlockEntity owner, Player first, Player second,
                                   AbstractMachineMenu firstMenu, AbstractMachineMenu secondMenu,
                                   AbstractMachineMenu firstClient, AbstractMachineMenu secondClient) {
        first.containerMenu = firstMenu;
        second.containerMenu = secondMenu;
        var firstWire = new MenuWire(firstClient);
        var secondWire = new MenuWire(secondClient);
        firstMenu.setSynchronizer(firstWire);
        secondMenu.setSynchronizer(secondWire);
        helper.assertTrue(firstClient.inputIsolation().supported() && secondClient.inputIsolation().supported()
                        && !firstClient.inputIsolation().enabled() && !secondClient.inputIsolation().enabled(),
                "Both detached client menus receive supported/off through initial vanilla DataSlots");
        helper.assertTrue(firstMenu.clickMenuButton(first, InputIsolationMenuState.ENABLE), "First real viewer enables isolation");
        firstMenu.broadcastChanges();
        secondMenu.broadcastChanges();
        helper.assertTrue(firstWire.changes > 0 && secondWire.changes > 0
                        && firstClient.inputIsolation().enabled() && secondClient.inputIsolation().enabled(),
                "Two independent ContainerSynchronizers deliver the enable DataSlot to both detached menus");
        int firstChanges = firstWire.changes;
        int secondChanges = secondWire.changes;
        helper.assertTrue(firstMenu.clickMenuButton(first, InputIsolationMenuState.ENABLE), "Repeated enable is accepted idempotently");
        firstMenu.broadcastChanges();
        secondMenu.broadcastChanges();
        helper.assertTrue(firstWire.changes == firstChanges && secondWire.changes == secondChanges,
                "Idempotent set produces no redundant DataSlot change for either viewer");
        CompoundTag saved = owner.saveWithoutMetadata(helper.getLevel().registryAccess());
        helper.assertTrue(secondMenu.clickMenuButton(second, InputIsolationMenuState.DISABLE), "Second real viewer disables isolation");
        firstMenu.broadcastChanges();
        secondMenu.broadcastChanges();
        helper.assertTrue(!firstClient.inputIsolation().enabled() && !secondClient.inputIsolation().enabled()
                        && firstWire.changes > firstChanges && secondWire.changes > secondChanges,
                "A change from the second player reaches both clients through vanilla data synchronization");
        owner.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
        firstMenu.broadcastChanges();
        secondMenu.broadcastChanges();
        helper.assertTrue(firstClient.inputIsolation().enabled() && secondClient.inputIsolation().enabled(),
                "Registry-backed block entity reload republishes the persisted state to both open menus");
    }

    public static void paidOrdinaryScopeSurvivesReload(GameTestHelper helper) {
        paidScopeSurvivesReload(helper, false);
    }

    public static void paidIsolatedScopeSurvivesReload(GameTestHelper helper) {
        paidScopeSurvivesReload(helper, true);
    }

    private static void paidScopeSurvivesReload(GameTestHelper helper, boolean isolated) {
        try (var fixture = fixture(helper, "paid_" + isolated, "item_input_bus", "item_input_bus", "energy_input_hatch")) {
            var first = fixture.ports.get(0);
            var other = fixture.ports.get(1);
            var energy = (EnergyInputHatchBlockEntity) fixture.ports.get(2);
            first.setInputIsolated(isolated);
            other.setInputIsolated(true);
            var catalyst = new ItemStack(Items.DIAMOND_PICKAXE);
            catalyst.set(DataComponents.DAMAGE, 7);
            var key = ItemResource.of(catalyst);
            insert(helper, first.itemStorage(), key, 1);
            energy.getEnergyStorage().setAmount(100);
            var components = new DataComponentPredicateSet(Map.of(DataComponents.DAMAGE, ComponentPredicate.exact(new JsonPrimitive(7))));
            var retained = new ItemRequirement(IOType.INPUT, Ingredient.of(Items.DIAMOND_PICKAXE), 1, ItemStack.EMPTY,
                    1F, List.of(), components, 0F);
            var recipe = recipe(fixture, "retained", 4, List.of(retained, new EnergyRequirement(5)), List.of());
            fixture.registerRecipe(recipe);
            var runtime = fixture.runtime;
            helper.assertTrue(runtime.start(recipe, 1).isCrafting(), "Ticker-qualified real controller starts a component-bearing retained ingredient");
            InputScope paidScope = runtime.activeInputScope();
            helper.assertTrue(paidScope.kind() == (isolated ? InputScope.Kind.ISOLATED : InputScope.Kind.ORDINARY)
                            && paidScope.sources().equals(List.of(first.getBlockPos())),
                    "Paid start freezes its exact physical ordinary or isolated source");
            var installed = runtime.ensureInstalledProgram();
            helper.assertTrue(installed != null, "Paid registered runtime installs a real execution program");
            long capabilityVersion = fixture.controller.componentRuntime().capabilityVersion();
            first.setInputIsolated(!isolated);
            other.setInputIsolated(false);
            fixture.controller.setFullyInputIsolated(true);
            fixture.controller.setFullyInputIsolated(false);
            helper.assertTrue(installed.current() && runtime.activeInputScope().equals(paidScope)
                            && fixture.controller.componentRuntime().capabilityVersion() == capabilityVersion,
                    "Live full/local changes do not cancel the installed paid execution or change its capability identity");
            var ledger = runtime.ledger().state();
            var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
            runtime.save(saved);
            var portSave = first.saveWithoutMetadata(helper.getLevel().registryAccess());
            runtime.invalidate();
            first.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), portSave));
            runtime.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved.buildResult()),
                    fixture.controller.resourceDomain());
            runtime.rebindCurrentVersions();
            helper.assertTrue(runtime.active() && runtime.activeInputScope().equals(paidScope) && runtime.ledger().state().equals(ledger)
                            && first.itemStorage().resource(0).equals(key),
                    "Registry-backed runtime and port NBT preserve paid scope, ledger and ItemStack DataComponents");
            installed = runtime.ensureInstalledProgram();
            helper.assertTrue(installed != null, "Restored paid execution installs without choosing a new input source");
            long before = energy.getEnergyStorage().amount();
            long round = fixture.nextRound();
            helper.assertTrue(installed.tryAttempt(round, helper.getLevel().getGameTime()) && installed.advance(),
                    "Explicit restored execution pays from the original retained source after configuration switches");
            helper.assertTrue(runtime.tickCount() == 1 && energy.getEnergyStorage().amount() == before - 5,
                    "Restored formal attempt advances once and pays actual shared energy once");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertTrue(first.itemStorage().extract(0, key, 1, transaction) == 1, "Original component-bearing catalyst really moves out");
                helper.assertTrue(other.itemStorage().insert(0, key, 1, transaction) == 1, "Identical catalyst really moves to another now-ordinary port");
                transaction.commit();
            }
            before = energy.getEnergyStorage().amount();
            ledger = runtime.ledger().state();
            round = fixture.nextRound();
            helper.assertTrue(installed.tryAttempt(round, helper.getLevel().getGameTime()), "Missing pinned catalyst receives a formal attempt");
            helper.assertFalse(installed.advance(), "Paid ordinary and isolated executions cannot migrate to the identical catalyst in another source");
            helper.assertTrue(runtime.tickCount() == 1 && energy.getEnergyStorage().amount() == before
                            && runtime.ledger().state().equals(ledger) && other.itemStorage().resource(0).equals(key)
                            && other.itemStorage().amount(0) == 1 && runtime.activeInputScope().equals(paidScope),
                    "Rejected migration preserves progress, payment, component-bearing catalyst and paid physical scope");
        }
        helper.succeed();
    }

    public static void nativeAeInputUsesOnePhysicalSource(GameTestHelper helper) {
        try (var fixture = fixture(helper, "ae_input", "ae2_me_input_interface", "fluid_input_hatch", "energy_input_hatch")) {
            var input = (InputInterfaceBlockEntity) fixture.ports.get(0);
            var outside = fixture.ports.get(1);
            var energy = (EnergyInputHatchBlockEntity) fixture.ports.get(2);
            fixture.controller.setFullyInputIsolated(true);
            var nativeStorage = input.getInterfaceLogic().getStorage();
            nativeStorage.setStack(0, new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2));
            insert(helper, outside.fluidStorage(), FluidResource.of(Fluids.WATER), 250);
            energy.getEnergyStorage().setAmount(20);
            var requirements = List.<MachineRequirement>of(item(2), water(250), new EnergyRequirement(7));
            helper.assertFalse(fixture.context().planRequirements(requirements, 1, Map.of()).successful(),
                    "Native AE item cache cannot borrow water from another isolated physical interface");
            helper.assertTrue(nativeStorage.getStack(0).amount() == 2 && outside.fluidStorage().amount(0) == 250
                            && energy.getEnergyStorage().amount() == 20,
                    "Rejected AE mixed input leaves native storage, outside water and energy unchanged");
            nativeStorage.setStack(1, new GenericStack(AEFluidKey.of(Fluids.WATER), 250));
            var plan = fixture.context().planRequirements(requirements, 1, Map.of());
            helper.assertTrue(input.itemStorage().reservationIdentity() == nativeStorage
                            && input.fluidStorage().reservationIdentity() == nativeStorage
                            && plan.successful() && plan.plan().commit(),
                    "Native AE item and fluid views retain one reservation identity and pay one complete isolated source");
            helper.assertTrue(nativeStorage.getStack(0) == null && nativeStorage.getStack(1) == null
                            && outside.fluidStorage().amount(0) == 250 && energy.getEnergyStorage().amount() == 13,
                    "Actual AE cache commit consumes only the selected interface and shared FE");
        }
        helper.succeed();
    }

    public static void ordinaryPatternRequestIsAtomic(GameTestHelper helper) {
        patternRequestIsAtomic(helper, false);
    }

    public static void isolatedPatternRequestIsAtomic(GameTestHelper helper) {
        patternRequestIsAtomic(helper, true);
    }

    private static void patternRequestIsAtomic(GameTestHelper helper, boolean isolated) {
        try (var fixture = fixture(helper, "pattern_" + isolated, "ae2_me_pattern_interface", "combined_input_basic", "energy_input_hatch")) {
            var host = (PatternInterfaceBlockEntity) fixture.ports.get(0);
            var outside = fixture.ports.get(1);
            var energy = (EnergyInputHatchBlockEntity) fixture.ports.get(2);
            host.setInputIsolated(isolated);
            outside.setInputIsolated(!isolated);
            insert(helper, outside.itemStorage(), ItemResource.of(Items.IRON_INGOT), 10);
            insert(helper, outside.fluidStorage(), FluidResource.of(Fluids.WATER), 1_000);
            energy.getEnergyStorage().setAmount(20);
            var outputs = List.<MachineOutput>of(new MachineOutput.ItemOutput(new ItemStack(Items.GOLD_NUGGET), 1F));
            var recipe = recipe(fixture, "request", 4, List.of(item(2), water(250), new EnergyRequirement(7),
                    new ItemRequirement(IOType.OUTPUT, null, 0, new ItemStack(Items.GOLD_NUGGET))), outputs);
            fixture.registerRecipe(recipe);
            var iron = AEItemKey.of(Items.IRON_INGOT);
            var water = AEFluidKey.of(Fluids.WATER);
            var holder = new KeyCounter();
            holder.add(iron, 1);
            holder.add(water, 250);
            host.getLogic().getReturnInv().setStack(0, new GenericStack(iron, 9));
            var itemRequest = host.itemRequestStorage(new KeyCounter[]{holder});
            var fluidRequest = host.fluidRequestStorage(new KeyCounter[]{holder});
            List<MachineCapability> requests = List.of(
                    new ItemBusCapability(host, itemRequest, INPUT),
                    new FluidHatchCapability(host, fluidRequest, INPUT));
            var insufficient = host.reservePatternStart(outputs, requests);
            helper.assertTrue(insufficient.status() == PatternStartReservation.Status.UNAVAILABLE,
                    "Real linked pattern host rejects an incomplete request without borrowing another input group or return inventory");
            helper.assertTrue(holder.get(iron) == 1 && holder.get(water) == 250 && outside.itemStorage().amount(0) == 10
                            && outside.fluidStorage().amount(0) == 1_000 && energy.getEnergyStorage().amount() == 20
                            && host.getLogic().getReturnInv().getAvailableStacks().get(iron) == 9 && !fixture.runtime.active(),
                    "Rejected native request preserves every holder, outside input, returned item and real energy balance");
            holder.add(iron, 1);
            // Native request views capture amounts at construction; a new push needs fresh views.
            var completeRequest = new PatternRequestState(new KeyCounter[]{holder}, 2);
            var completeItems = AE2ResourceFamilies.ITEM.patternRequestView(completeRequest);
            var completeFluids = AE2ResourceFamilies.FLUID.patternRequestView(completeRequest);
            List<MachineCapability> completeCapabilities = List.of(new ItemBusCapability(host, completeItems, INPUT),
                    new FluidHatchCapability(host, completeFluids, INPUT));
            helper.assertTrue(completeItems.amount(0) == 2 && completeFluids.amount(0) == 250,
                    "Fresh native request views capture the complete item/fluid push");
            var stale = host.reservePatternStart(outputs, completeCapabilities);
            helper.assertTrue(stale.status() == PatternStartReservation.Status.RESERVED,
                    "Complete real item/fluid request reserves the ticker-qualified linked controller");
            energy.getEnergyStorage().setAmount(0);
            helper.assertFalse(stale.commit(transaction -> {
                throw new AssertionError("Native request acceptance cannot run after the live energy check fails");
            }), "Energy loss after host reservation rejects actual pattern admission");
            helper.assertTrue(holder.get(iron) == 2 && holder.get(water) == 250 && !fixture.runtime.active()
                            && completeItems.amount(0) == 2 && completeFluids.amount(0) == 250
                            && outside.itemStorage().amount(0) == 10 && outside.fluidStorage().amount(0) == 1_000,
                    "Pattern transaction rollback restores both request families without spending outside material");
            energy.getEnergyStorage().setAmount(20);
            var complete = host.reservePatternStart(outputs, completeCapabilities);
            helper.assertTrue(complete.status() == PatternStartReservation.Status.RESERVED && complete.commit(transaction -> {
                helper.assertTrue(completeItems.accept(host.itemReturnStorage(), transaction)
                                && completeFluids.accept(host.fluidReturnStorage(), transaction),
                        "Native item/fluid acceptance clears the real holders in the same root transaction");
            }),
                    "Complete request retries and commits through the native host admission boundary");
            helper.assertTrue(holder.get(iron) == 0 && holder.get(water) == 0 && energy.getEnergyStorage().amount() == 20
                            && outside.itemStorage().amount(0) == 10 && outside.fluidStorage().amount(0) == 1_000
                            && host.getLogic().getReturnInv().getAvailableStacks().get(iron) == 9,
                    "Successful pattern admission pays request holders but only checks shared FE, preserving other inputs and return inventory");
            helper.assertTrue(fixture.runtime.activeInputScope().kind() == (isolated ? InputScope.Kind.ISOLATED : InputScope.Kind.ORDINARY)
                            && fixture.runtime.activeInputScope().sources().equals(List.of(host.getBlockPos())),
                    "Pattern runtime freezes the real ordinary/isolated host position rather than temporary capability indices");
        }
        helper.succeed();
    }

    public static void ordinaryPatternRequestCanTopUpAndRollback(GameTestHelper helper) {
        try (var fixture = fixture(helper, "pattern_ordinary_topup", "ae2_me_pattern_interface", "combined_input_basic", "energy_input_hatch")) {
            var host = (PatternInterfaceBlockEntity) fixture.ports.get(0);
            var ordinary = fixture.ports.get(1);
            var energy = (EnergyInputHatchBlockEntity) fixture.ports.get(2);
            helper.assertTrue(!host.isInputIsolated() && !ordinary.isInputIsolated() && !fixture.controller.isFullyInputIsolated(),
                    "Both real material sources belong to the same ordinary group");
            insert(helper, ordinary.itemStorage(), ItemResource.of(Items.IRON_INGOT), 1);
            energy.getEnergyStorage().setAmount(20);
            var outputs = List.<MachineOutput>of(new MachineOutput.ItemOutput(new ItemStack(Items.GOLD_NUGGET), 1F));
            var recipe = recipe(fixture, "request", 4, List.of(item(2), water(250), new EnergyRequirement(7),
                    new ItemRequirement(IOType.OUTPUT, null, 0, new ItemStack(Items.GOLD_NUGGET))), outputs);
            fixture.registerRecipe(recipe);
            var iron = AEItemKey.of(Items.IRON_INGOT);
            var water = AEFluidKey.of(Fluids.WATER);
            host.getLogic().getReturnInv().setStack(0, new GenericStack(iron, 9));
            host.getLogic().getReturnInv().setStack(1, new GenericStack(water, 1_000));
            var holder = new KeyCounter();
            holder.add(iron, 1);
            holder.add(water, 125);
            var request = new PatternRequestState(new KeyCounter[]{holder}, 2);
            var items = AE2ResourceFamilies.ITEM.patternRequestView(request);
            var fluids = AE2ResourceFamilies.FLUID.patternRequestView(request);
            List<MachineCapability> capabilities = List.of(new ItemBusCapability(host, items, INPUT),
                    new FluidHatchCapability(host, fluids, INPUT));
            helper.assertTrue(host.reservePatternStart(outputs, capabilities).status() == PatternStartReservation.Status.UNAVAILABLE,
                    "Even ordinary requests cannot use native returned water as recipe input");
            helper.assertTrue(items.amount(0) == 1 && fluids.amount(0) == 125 && ordinary.itemStorage().amount(0) == 1
                            && energy.getEnergyStorage().amount() == 20 && holder.get(iron) == 1 && holder.get(water) == 125,
                    "Missing ordinary fluid does not partially consume the real request or physical inventory");
            insert(helper, ordinary.fluidStorage(), FluidResource.of(Fluids.WATER), 125);
            var prepared = host.reservePatternStart(outputs, capabilities);
            helper.assertTrue(prepared.status() == PatternStartReservation.Status.RESERVED,
                    "Ordinary host request and same-group physical item/fluid input can jointly reserve one full recipe");
            try (Transaction transaction = Transaction.openRoot()) {
                helper.assertTrue(ordinary.fluidStorage().extract(0, FluidResource.of(Fluids.WATER), 125, transaction) == 125,
                        "External transfer removes the prepared ordinary fluid");
                transaction.commit();
            }
            helper.assertFalse(prepared.commit(transaction -> {
                throw new AssertionError("Native request acceptance cannot run after ordinary top-up fails");
            }), "Lost ordinary top-up rejects the real mixed material payment");
            helper.assertTrue(prepared.rolledBack() && !fixture.runtime.active() && items.amount(0) == 1 && fluids.amount(0) == 125
                            && holder.get(iron) == 1 && holder.get(water) == 125 && ordinary.itemStorage().amount(0) == 1
                            && ordinary.fluidStorage().amount(0) == 0 && energy.getEnergyStorage().amount() == 20,
                    "Failure rolls back request and physical item deductions while preserving the external water removal");
            insert(helper, ordinary.fluidStorage(), FluidResource.of(Fluids.WATER), 125);
            var retry = host.reservePatternStart(outputs, capabilities);
            helper.assertTrue(retry.status() == PatternStartReservation.Status.RESERVED && retry.commit(transaction -> {
                helper.assertTrue(items.accept(host.itemReturnStorage(), transaction) && fluids.accept(host.fluidReturnStorage(), transaction),
                        "Successful ordinary payment accepts both native request families atomically");
            }), "Same-group ordinary top-up succeeds after the external fluid is restored");
            helper.assertTrue(holder.get(iron) == 0 && holder.get(water) == 0 && ordinary.itemStorage().amount(0) == 0
                            && ordinary.fluidStorage().amount(0) == 0 && energy.getEnergyStorage().amount() == 20
                            && host.getLogic().getReturnInv().getAvailableStacks().get(iron) == 9
                            && host.getLogic().getReturnInv().getAvailableStacks().get(water) == 1_000,
                    "Joint ordinary admission consumes request plus physical ingredients without consuming shared FE or returned resources");
            helper.assertTrue(fixture.runtime.activeInputScope().kind() == InputScope.Kind.ORDINARY
                            && fixture.runtime.activeInputScope().sources().equals(List.of(host.getBlockPos(), ordinary.getBlockPos())),
                    "Paid ordinary execution freezes the real host and same-group physical source positions");
        }
        helper.succeed();
    }

    public static void chemicalIsolationStillSharesHeatAndEnergy(GameTestHelper helper) {
        try (var fixture = fixture(helper, "chemical", "chemical_input_hatch_basic", "chemical_input_hatch_basic",
                "heat_input_hatch", "energy_input_hatch")) {
            var first = (ChemicalPortBlockEntity) fixture.ports.get(0);
            var second = (ChemicalPortBlockEntity) fixture.ports.get(1);
            var heat = (HeatPortBlockEntity) fixture.ports.get(2);
            var energy = (EnergyInputHatchBlockEntity) fixture.ports.get(3);
            fixture.controller.setFullyInputIsolated(true);
            var oxygen = MekanismChemicals.OXYGEN.asResource();
            first.chemicalTank().setContents(oxygen, 500, null);
            second.chemicalTank().setContents(oxygen, 500, null);
            try (Transaction transaction = Transaction.openRoot()) {
                heat.heatCapacitor().setHeat(800 * heat.heatCapacitor().getHeatCapacity(), transaction);
                transaction.commit();
            }
            double beforeHeat = heat.heatCapacitor().getHeat();
            energy.getEnergyStorage().setAmount(20);
            var requirements = List.<MachineRequirement>of(LoadedChemicalRequirement.input(ChemicalIngredient.chemical(
                    Identifier.parse("mekanism:oxygen"), 1_000)),
                    new EnergyRequirement(7), LoadedHeatRequirement.minimumTemperature(600));
            helper.assertFalse(fixture.context().planRequirements(requirements, 1, Map.of()).successful(),
                    "Full isolation cannot pool actual registered Mekanism chemical tanks");
            helper.assertTrue(first.chemicalTank().amountAsLong() == 500 && second.chemicalTank().amountAsLong() == 500
                            && energy.getEnergyStorage().amount() == 20 && heat.heatCapacitor().getHeat() == beforeHeat,
                    "Chemical rejection preserves both tanks, heat and energy");
            second.chemicalTank().setContents(oxygen, 1_000, null);
            var complete = fixture.context().planRequirements(requirements, 1, Map.of());
            helper.assertTrue(complete.successful() && complete.plan().inputScope().sources().equals(List.of(second.getBlockPos()))
                            && complete.plan().commit(), "One complete chemical source shares a separate registered heat input and FE hatch");
            helper.assertTrue(first.chemicalTank().amountAsLong() == 500 && second.chemicalTank().amountAsLong() == 0
                            && energy.getEnergyStorage().amount() == 13 && heat.heatCapacitor().getHeat() == beforeHeat,
                    "Chemical commit selects one tank, pays shared FE and treats shared temperature as read-only");
        }
        helper.succeed();
    }

    private static ItemRequirement item(int count) {
        return new ItemRequirement(IOType.INPUT, Ingredient.of(Items.IRON_INGOT), count, ItemStack.EMPTY);
    }

    private static FluidRequirement water(int amount) {
        return new FluidRequirement(IOType.INPUT, FluidIngredient.of(Fluids.WATER), amount, FluidStack.EMPTY);
    }

    private static <R> void insert(GameTestHelper helper, ResourceStorage<R> storage, R resource, long amount) {
        try (Transaction transaction = Transaction.openRoot()) {
            helper.assertTrue(storage.insert(0, resource, amount, transaction) == amount, "Registered storage accepts the complete fixture resource");
            transaction.commit();
        }
    }

    private static MachineRecipe recipe(Fixture fixture, String name, int duration,
                                        List<MachineRequirement> requirements, List<MachineOutput> outputs) {
        return MachineRecipe.fromCanonical(fixture.controller.currentRecipePoolId().withSuffix("_" + name),
                fixture.controller.currentRecipePoolId(), duration, requirements, outputs, List.of(), 0, 1,
                false, false, false, Set.of());
    }

    private static Fixture fixture(GameTestHelper helper, String name, String... portIds) {
        BlockPos controllerPos = new BlockPos(0, 1, 0);
        var base = MMCR.id("test_cube");
        helper.setBlock(controllerPos, ModBlocks.controllerFor(base).get().defaultBlockState()
                .setValue(MachineControllerBlock.FACING, Direction.SOUTH)
                .setValue(MachineControllerBlock.ROLL_FACING, Direction.NORTH));
        Map<BlockPos, BlockPredicate> pattern = new LinkedHashMap<>();
        List<IOPortBlockEntity> ports = new ArrayList<>();
        for (int index = 0; index < portIds.length; index++) {
            var pos = new BlockPos(index + 1, 1, 0);
            var block = ModBlocks.BLOCKS.get(portIds[index]).get();
            helper.setBlock(pos, block.defaultBlockState());
            ports.add(helper.getBlockEntity(pos, IOPortBlockEntity.class));
            pattern.put(pos.subtract(controllerPos), new BlockPredicate.OfBlock(block));
        }
        var machineId = MMCR.id("input_isolation_" + name);
        var machine = new DynamicMachine(machineId, name, new BlockArray(pattern),
                MachineControllerSpec.defaultsFor(machineId), PortRequirementSpec.none());
        var controller = helper.getBlockEntity(controllerPos, MachineControllerBlockEntity.class);
        try {
            var mode = MachineControllerBlockEntity.class.getDeclaredField("activeWorkMode");
            mode.setAccessible(true);
            var runtime = MachineControllerBlockEntity.class.getDeclaredField("runtime");
            runtime.setAccessible(true);
            var fixture = new Fixture(helper, controller, ports, ((MachineControllerRuntime) runtime.get(controller)).craftingRuntime(), machineId);
            try {
                helper.assertFalse(MachineRegistry.containsStatic(machineId), "Fixture machine registration starts clean");
                fixture.machineRegistered = true;
                MachineRegistry.register(machine);
                controller.setMachine(machine);
                var coordinator = SharedIoCoordinator.get(helper.getLevel());
                coordinator.beginExecutionRound(helper.getLevel().getGameTime(), true);
                controller.serverTick();
                helper.assertTrue(controller.currentStructureSnapshot().formed() && controller.currentStructureSnapshot().structureAreaLoaded()
                                && controller.resourceDomain() != null, "Registered fixture forms and binds ports at an explicit controller tick");
                helper.assertTrue(coordinator.controllerPermit(controller.getBlockPos()).eligible(coordinator.executionRoundId(),
                                helper.getLevel().getGameTime(), true), "Real controller tick grants production execution admission");
                mode.set(controller, MachineWorkMode.SYNC);
                return fixture;
            } catch (ReflectiveOperationException | RuntimeException | Error failure) {
                fixture.close();
                throw failure;
            }
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot access attached runtime fixture", exception);
        }
    }

    /** Explicit execution rounds avoid scheduler timing assertions and restore the shared clock.
     * @author howxu <dev@howxu.cn>
     */
    private static final class Fixture implements AutoCloseable {
        private final GameTestHelper helper;
        private final MachineControllerBlockEntity controller;
        private final List<IOPortBlockEntity> ports;
        private final CraftingRuntime runtime;
        private final long originalTime;
        private final Identifier machineId;
        private final Map<Identifier, Machine> previousMachines;
        private final Map<Identifier, MachineRecipe> previousDynamicRecipes;
        private boolean machineRegistered;
        private boolean recipesChanged;

        private Fixture(GameTestHelper helper, MachineControllerBlockEntity controller,
                        List<IOPortBlockEntity> ports, CraftingRuntime runtime, Identifier machineId) {
            this.helper = helper;
            this.controller = controller;
            this.ports = List.copyOf(ports);
            this.runtime = runtime;
            this.machineId = machineId;
            previousMachines = MachineRegistry.effectiveSnapshot();
            previousDynamicRecipes = RecipeRegistry.dynamicSnapshot();
            originalTime = helper.getLevel().getGameTime();
        }

        private CraftingContext context() { return new CraftingContext(controller.componentRuntime().capabilitySnapshot()); }

        private void registerRecipe(MachineRecipe recipe) {
            var recipes = new LinkedHashMap<>(RecipeRegistry.dynamicSnapshot());
            recipes.put(recipe.id(), recipe);
            recipesChanged = true;
            RecipeRegistry.replaceDynamic(recipes);
            helper.assertTrue(RecipeRegistry.getRecipe(recipe.id()) == recipe, "Scoped fixture recipe is registered in its real machine pool");
        }

        private long nextRound() {
            long next = helper.getLevel().getGameTime() + 1;
            ((ServerLevelData) helper.getLevel().getLevelData()).setGameTime(next);
            return SharedIoCoordinator.get(helper.getLevel()).beginExecutionRound(next, true);
        }

        @Override public void close() {
            try {
                runtime.invalidate();
                SharedIoCoordinator.get(helper.getLevel()).cancel(controller.getBlockPos());
                controller.releaseStructureClaims();
            } finally {
                ((ServerLevelData) helper.getLevel().getLevelData()).setGameTime(originalTime);
                try {
                    if (recipesChanged) RecipeRegistry.replaceDynamic(previousDynamicRecipes);
                } finally {
                    if (machineRegistered) removeFixtureMachine();
                }
                helper.assertTrue(MachineRegistry.effectiveSnapshot().equals(previousMachines)
                                && RecipeRegistry.dynamicSnapshot().equals(previousDynamicRecipes),
                        "Synchronous fixture cleanup restores pre-existing machine and dynamic recipe snapshots");
            }
        }

        // MachineRegistry has no removal API; remove only this synchronous fixture's own entry.
        @SuppressWarnings("unchecked")
        private void removeFixtureMachine() {
            synchronized (RuntimeContentVersion.lock()) {
                try {
                    for (String name : List.of("STATIC_MACHINES", "COMPILED", "EFFECTIVE_MACHINES")) {
                        var field = MachineRegistry.class.getDeclaredField(name);
                        field.setAccessible(true);
                        var current = (Map<Identifier, ?>) field.get(null);
                        if (name.equals("STATIC_MACHINES")) current.remove(machineId);
                        else {
                            var remaining = new LinkedHashMap<>(current);
                            remaining.remove(machineId);
                            field.set(null, Map.copyOf(remaining));
                        }
                    }
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError("Cannot clean up scoped fixture machine registration", exception);
                }
            }
        }
    }

    /** Delivers vanilla data messages into a detached client menu, one independent wire per viewer.
     * @author howxu <dev@howxu.cn>
     */
    private static final class MenuWire implements ContainerSynchronizer {
        private final AbstractContainerMenu client;
        private int changes;

        private MenuWire(AbstractContainerMenu client) { this.client = client; }
        @Override public void sendInitialData(AbstractContainerMenu menu, List<ItemStack> items, ItemStack carried, int[] data) {
            for (int index = 0; index < data.length; index++) client.setData(index, data[index]);
        }
        @Override public void sendDataChange(AbstractContainerMenu menu, int id, int value) {
            changes++;
            client.setData(id, value);
        }
        @Override public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) { }
        @Override public void sendCarriedChange(AbstractContainerMenu menu, ItemStack stack) { }
        @Override public RemoteSlot createSlot() {
            return new RemoteSlot.Synchronized(component -> { throw new AssertionError("No client hash updates in menu isolation fixture"); });
        }
    }
}
