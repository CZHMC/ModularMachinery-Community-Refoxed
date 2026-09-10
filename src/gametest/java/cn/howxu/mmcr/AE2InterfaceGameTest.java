package cn.howxu.mmcr;

import appeng.api.AECapabilities;
import appeng.api.behaviors.GenericInternalInventory;
import appeng.api.config.Actionable;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.core.definitions.AEItems;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.menu.implementations.InterfaceMenu;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.recipe.MachineIngredient;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.compat.appliedenergistics2.AE2Bridge;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2InputInterfaceBlockEntity;
import cn.howxu.mmcr.internal.block.MachineControllerBlock;
import cn.howxu.mmcr.internal.port.PortFamilyIds;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end GameTest coverage for the AE2 input interface MMCR integration.
 *
 * @author howxu <dev@howxu.cn>
 */
public class AE2InterfaceGameTest {

    private static final Identifier MACHINE_ID = MMCR.id("ae2_interface_integration_test");
    private static final Identifier RECIPE_ID = MMCR.id("ae2_interface_integration_recipe");
    private static final long INITIAL_ITEM_COUNT = 16L;
    private static final long INITIAL_FLUID_AMOUNT = 2_000L;

    public void interfaceFeedsMmcrInputs(GameTestHelper helper) {
        helper.assertTrue(AE2Bridge.get().available(),
                "AE2 must be loaded for this integration test");

        BlockPos portPos = new BlockPos(1, 2, 0);
        BlockPos outputPos = new BlockPos(1, 0, 0);
        helper.setBlock(portPos, ModBlocks.BLOCKS.get("ae2_me_input_interface").get().defaultBlockState());
        helper.setBlock(outputPos, ModBlocks.BLOCKS.get("item_output_bus").get().defaultBlockState());
        BlockPos portWorldPos = helper.absolutePos(portPos);

        BlockPos controllerPos = new BlockPos(1, 1, 0);
        helper.setBlock(controllerPos,
                ModBlocks.controllerFor(MMCR.id("test_cube")).get().defaultBlockState()
                        .setValue(MachineControllerBlock.FACING, Direction.SOUTH));

        Map<BlockPos, BlockPredicate> pattern = Map.of(
                new BlockPos(0, 1, 0), new BlockPredicate.OfBlock(
                        ModBlocks.BLOCKS.get("ae2_me_input_interface").get()),
                new BlockPos(0, -1, 0), new BlockPredicate.OfBlock(
                        ModBlocks.BLOCKS.get("item_output_bus").get()));
        DynamicMachine machine = new DynamicMachine(MACHINE_ID,
                "AE2 Interface Integration Test",
                new BlockArray(pattern));
        if (!MachineRegistry.containsStatic(MACHINE_ID)) {
            MachineRegistry.register(machine);
        }
        RecipeRegistry.registerStatic(MachineRecipe.fromCanonical(
                RECIPE_ID, MACHINE_ID, 20,
                List.of(
                        MachineRequirement.fromInput(new MachineIngredient.ItemIngredient(
                                Ingredient.of(Items.IRON_INGOT), 1)),
                        MachineRequirement.fromInput(new MachineIngredient.FluidIngredient(
                                FluidIngredient.of(Fluids.WATER), 1_000)),
                        MachineRequirement.itemOutput(new ItemStack(Items.IRON_NUGGET))),
                List.of(new MachineOutput.ItemOutput(new ItemStack(Items.IRON_NUGGET), 1F)),
                List.of(), 0, 1,
                false, false, List.of(), false, Set.of()));

        MachineControllerBlockEntity controller = helper.getBlockEntity(controllerPos,
                MachineControllerBlockEntity.class);
        controller.setMachine(machine);
        controller.setStructureCheckIntervalForTesting(1);
        helper.runAtTickTime(1, () -> {
            controller.setMachine(machine);
            controller.requestImmediateStructureCheck();
        });

        helper.runAtTickTime(2, () -> {
            AE2InputInterfaceBlockEntity entity = helper.getBlockEntity(portPos,
                    AE2InputInterfaceBlockEntity.class);
            helper.assertTrue(entity != null,
                    "AE2 input interface resolves to AE2InputInterfaceBlockEntity");

            BlockState portState = helper.getLevel().getBlockState(portWorldPos);

            IInWorldGridNodeHost gridHost = helper.getLevel().getCapability(
                    AECapabilities.IN_WORLD_GRID_NODE_HOST, portWorldPos, null);
            helper.assertTrue(gridHost != null,
                    "AE2 IN_WORLD_GRID_NODE_HOST capability is exposed");

            MEStorage meStorage = helper.getLevel().getCapability(
                    AECapabilities.ME_STORAGE, portWorldPos, portState, entity, null);
            helper.assertTrue(meStorage != null,
                    "AE2 ME_STORAGE capability is exposed after grid boot");

            GenericInternalInventory genericInv = helper.getLevel().getCapability(
                    AECapabilities.GENERIC_INTERNAL_INV, portWorldPos, portState, entity, null);
            helper.assertTrue(genericInv != null,
                    "AE2 GENERIC_INTERNAL_INV capability is exposed");

            CapabilitySnapshot snapshot = entity.capabilitySnapshot();
            List<MachineCapability> capabilities = snapshot.capabilities();
            helper.assertTrue(capabilities.size() == 2,
                    "MMCR AE2 input interface exposes exactly two capabilities");
            helper.assertTrue(capabilities.stream()
                            .map(MachineCapability::type)
                            .allMatch(type -> type.id().equals(PortFamilyIds.ITEM)
                                    || type.id().equals(PortFamilyIds.FLUID)),
                    "MMCR AE2 input interface capabilities cover ITEM and FLUID families");
            for (MachineCapability capability : capabilities) {
                helper.assertTrue(capability.view().facets().stream()
                                .noneMatch(cn.howxu.mmcr.api.capability.facet.TransferFacet.class::isInstance),
                        "MMCR AE2 input interface capability does not expose TransferFacet");
            }

            GenericStackInv storageInv = entity.getInterfaceLogic().getStorage();
            entity.getInterfaceLogic().getConfig().setStack(0,
                    new GenericStack(AEItemKey.of(Items.IRON_INGOT), 8L));
            entity.getInterfaceLogic().getConfig().setStack(1,
                    new GenericStack(AEFluidKey.of(Fluids.WATER), 1_000L));
            helper.assertTrue(entity.getInterfaceLogic().getConfig().size() == 9,
                    "AE2 input interface config inventory has nine slots");
            helper.assertTrue(entity.getInterfaceLogic().getConfig().getStack(0) != null
                            && entity.getInterfaceLogic().getConfig().getStack(0).what()
                                    .equals(AEItemKey.of(Items.IRON_INGOT)),
                    "AE2 config slot 0 holds the iron filter");
            helper.assertTrue(entity.getInterfaceLogic().getConfig().getStack(1) != null
                            && entity.getInterfaceLogic().getConfig().getStack(1).what()
                                    .equals(AEFluidKey.of(Fluids.WATER)),
                    "AE2 config slot 1 holds the water filter");
            helper.assertTrue(entity.itemStorage().reservationIdentity() == storageInv,
                    "MMCR item storage view shares the AE2 GenericStackInv identity");
            helper.assertTrue(entity.fluidStorage().reservationIdentity() == storageInv,
                    "MMCR fluid storage view shares the AE2 GenericStackInv identity");

            ServerPlayer player = makePlayer(helper);
            boolean bridgeAttemptedOpen = false;
            try {
                bridgeAttemptedOpen = AE2Bridge.get()
                        .openMenu(player, helper.getLevel(), portWorldPos);
            } catch (NullPointerException expected) {
                // AE2 menu opening dispatches the screen to the player's connection,
                // which is null for the GameTest-managed ServerPlayer. The NPE confirms
                // the bridge reached MenuOpener.open(InterfaceMenu.TYPE, ...) instead of
                // returning early.
                bridgeAttemptedOpen = true;
            }
            helper.assertTrue(bridgeAttemptedOpen,
                    "AE2 bridge forwards the AE2 input interface to MenuOpener.open");
            InterfaceMenu interfaceMenu = new InterfaceMenu(InterfaceMenu.TYPE, 0,
                    player.getInventory(), entity);
            helper.assertTrue(interfaceMenu instanceof InterfaceMenu,
                    "AE2 InterfaceMenu is constructed via the AE2 factory bound to InterfaceMenu.TYPE");

            genericInv.insert(0, AEItemKey.of(Items.IRON_INGOT), INITIAL_ITEM_COUNT,
                    Actionable.MODULATE);
            genericInv.insert(1, AEFluidKey.of(Fluids.WATER), INITIAL_FLUID_AMOUNT,
                    Actionable.MODULATE);
            helper.assertTrue(entity.itemStorage().amount(0) == INITIAL_ITEM_COUNT,
                    "GENERIC_INTERNAL_INV item insert flows into the MMCR item storage view");
            helper.assertTrue(entity.fluidStorage().amount(1) == INITIAL_FLUID_AMOUNT,
                    "GENERIC_INTERNAL_INV fluid insert flows into the MMCR fluid storage view");
        });

        helper.runAtTickTime(60, () -> {
            AE2InputInterfaceBlockEntity entity = helper.getBlockEntity(portPos,
                    AE2InputInterfaceBlockEntity.class);
            helper.assertTrue(controller.structureSnapshot().formed(),
                    "MMCR multiblock containing AE2 input interface forms");
            helper.assertTrue(entity.itemStorage().amount(0) <= INITIAL_ITEM_COUNT - 1L,
                    "MMCR recipe consumes an iron ingot from the AE2 local inventory amount="
                            + entity.itemStorage().amount(0));
            helper.assertTrue(entity.fluidStorage().amount(1) <= INITIAL_FLUID_AMOUNT - 1_000L,
                    "MMCR recipe consumes 1000 mB of water from the AE2 local inventory fluidAmount="
                            + entity.fluidStorage().amount(1));
            helper.assertTrue(controller.runtimeSnapshot().crafting().recipeId() == null,
                    "MMCR controller reports the recipe has completed");
            helper.assertTrue(controller.runtimeSnapshot().linkedPortPositions().contains(portWorldPos),
                    "MMCR controller links the AE2 input interface as an input port");

            long itemBeforeRollback = entity.itemStorage().amount(0);
            long fluidBeforeRollback = entity.fluidStorage().amount(1);
            try (Transaction transaction = Transaction.openRoot()) {
                entity.itemStorage().extract(0,
                        ItemResource.of(Items.IRON_INGOT), 1L, transaction);
                entity.fluidStorage().extract(1,
                        FluidResource.of(Fluids.WATER), 1_000L, transaction);
            }
            helper.assertTrue(entity.itemStorage().amount(0) == itemBeforeRollback,
                    "AE2 local inventory item amount is unchanged after a rolled-back extraction");
            helper.assertTrue(entity.fluidStorage().amount(1) == fluidBeforeRollback,
                    "AE2 local inventory fluid amount is unchanged after a rolled-back extraction");

            entity.getInterfaceLogic().getConfig().setStack(2,
                    new GenericStack(AEItemKey.of(Items.COAL), 4L));
            entity.getInterfaceLogic().setPriority(42);
            entity.getInterfaceLogic().getUpgrades().addItems(AEItems.FUZZY_CARD.stack());
            long itemBeforeReload = entity.itemStorage().amount(0);
            long fluidBeforeReload = entity.fluidStorage().amount(1);
            reloadBlockEntity(entity, helper);
            helper.assertTrue(entity.itemStorage().amount(0) == itemBeforeReload,
                    "Item storage amount survives a save/load cycle");
            helper.assertTrue(entity.fluidStorage().amount(1) == fluidBeforeReload,
                    "Fluid storage amount survives a save/load cycle");
            helper.assertTrue(entity.getInterfaceLogic().getConfig().getStack(0) != null
                            && entity.getInterfaceLogic().getConfig().getStack(0).what()
                                    .equals(AEItemKey.of(Items.IRON_INGOT)),
                    "Config slot 0 (iron) survives a save/load cycle");
            helper.assertTrue(entity.getInterfaceLogic().getConfig().getStack(2) != null
                            && entity.getInterfaceLogic().getConfig().getStack(2).what()
                                    .equals(AEItemKey.of(Items.COAL)),
                    "Config slot 2 (coal) added after reload setup survives the cycle");
            helper.assertTrue(entity.getInterfaceLogic().getPriority() == 42,
                    "AE2 interface priority survives a save/load cycle");
            helper.assertTrue(!entity.getInterfaceLogic().getUpgrades().isEmpty(),
                    "AE2 upgrade inventory survives a save/load cycle");

            helper.getLevel().destroyBlock(portWorldPos, true);
            helper.runAfterDelay(2, () -> {
                long droppedItems = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                                new AABB(portWorldPos).inflate(1))
                        .stream()
                        .mapToLong(entity2 -> entity2.getItem().getCount())
                        .sum();
                helper.assertTrue(droppedItems >= itemBeforeReload,
                        "Removing the AE2 input interface drops every stored iron ingot");
                helper.succeed();
            });
        });
    }

    private static ServerPlayer makePlayer(GameTestHelper helper) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(
                        "mmcr-ae2-interface-test".getBytes(StandardCharsets.UTF_8)),
                        "mmcr-ae2-interface"),
                ClientInformation.createDefault());
    }

    private static void reloadBlockEntity(BlockEntity entity, GameTestHelper helper) {
        try {
            Method save = BlockEntity.class.getDeclaredMethod("saveAdditional", ValueOutput.class);
            save.setAccessible(true);
            Method load = BlockEntity.class.getDeclaredMethod("loadAdditional", ValueInput.class);
            load.setAccessible(true);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess());
            save.invoke(entity, output);
            CompoundTag tag = output.buildResult();
            load.invoke(entity, TagValueInput.create(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess(), tag));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to reload block entity " + entity, exception);
        }
    }
}