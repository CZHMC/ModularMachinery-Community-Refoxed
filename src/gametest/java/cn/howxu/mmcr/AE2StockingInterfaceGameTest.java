package cn.howxu.mmcr;

import appeng.api.AECapabilities;
import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityRequests;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.compat.appliedenergistics2.AE2Bridge;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2StockingInterfaceBlockEntity;
import cn.howxu.mmcr.internal.port.PortFamilyIds;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.List;

/**
 * End-to-end GameTest coverage for the AE2 stocking input interface.
 *
 * @author howxu <dev@howxu.cn>
 */
public class AE2StockingInterfaceGameTest {
    private static final long ITEM_AMOUNT = 16L;
    private static final long FLUID_AMOUNT = 2_000L;

    public void stockingInterfaceReadsAndWatchesNetworkStorage(GameTestHelper helper) {
        helper.assertTrue(AE2Bridge.get().available(),
                "AE2 must be loaded for this integration test");

        BlockPos portPos = new BlockPos(0, 0, 0);
        BlockPos itemChestPos = new BlockPos(3, 0, 0);
        BlockPos fluidChestPos = new BlockPos(3, 0, 2);
        BlockPos energyPos = new BlockPos(3, 0, 4);
        helper.setBlock(portPos,
                ModBlocks.BLOCKS.get("ae2_me_stocking_input_interface").get().defaultBlockState());
        helper.setBlock(itemChestPos, AEBlocks.ME_CHEST.block().defaultBlockState());
        helper.setBlock(fluidChestPos, AEBlocks.ME_CHEST.block().defaultBlockState());
        helper.setBlock(energyPos, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());

        AE2StockingInterfaceBlockEntity port = helper.getBlockEntity(portPos,
                AE2StockingInterfaceBlockEntity.class);
        MEChestBlockEntity itemChest = helper.getBlockEntity(itemChestPos, MEChestBlockEntity.class);
        MEChestBlockEntity fluidChest = helper.getBlockEntity(fluidChestPos, MEChestBlockEntity.class);
        CreativeEnergyCellBlockEntity energy = helper.getBlockEntity(energyPos,
                CreativeEnergyCellBlockEntity.class);

        itemChest.setCell(AEItems.ITEM_CELL_1K.stack());
        fluidChest.setCell(AEItems.FLUID_CELL_1K.stack());
        port.getInterfaceLogic().getConfig().setStack(0,
                new GenericStack(AEItemKey.of(Items.IRON_INGOT), 64L));
        port.getInterfaceLogic().getConfig().setStack(1,
                new GenericStack(AEFluidKey.of(Fluids.WATER), 1_000L));

        helper.runAtTickTime(2, () -> {
            helper.assertTrue(port.getMainNode().getNode() != null,
                    "Stocking interface has initialized its AE2 grid node");
            helper.assertTrue(itemChest.getMainNode().getNode() != null,
                    "Item ME Chest has initialized its AE2 grid node");
            helper.assertTrue(fluidChest.getMainNode().getNode() != null,
                    "Fluid ME Chest has initialized its AE2 grid node");
            helper.assertTrue(energy.getMainNode().getNode() != null,
                    "Creative energy cell has initialized its AE2 grid node");
            GridHelper.createConnection(port.getMainNode().getNode(), itemChest.getMainNode().getNode());
            GridHelper.createConnection(port.getMainNode().getNode(), fluidChest.getMainNode().getNode());
            GridHelper.createConnection(port.getMainNode().getNode(), energy.getMainNode().getNode());
        });

        helper.runAtTickTime(10, () -> {
            MEStorage itemStorage = itemChest.getInventory();
            MEStorage fluidStorage = fluidChest.getInventory();
            helper.assertTrue(itemStorage.insert(AEItemKey.of(Items.IRON_INGOT),
                            ITEM_AMOUNT, Actionable.MODULATE, IActionSource.empty()) == ITEM_AMOUNT,
                    "Item test storage accepts the configured item");
            helper.assertTrue(fluidStorage.insert(AEFluidKey.of(Fluids.WATER),
                            FLUID_AMOUNT, Actionable.MODULATE, IActionSource.empty()) == FLUID_AMOUNT,
                    "Fluid test storage accepts the configured fluid");
        });

        helper.runAtTickTime(11, () -> {
            BlockState portState = helper.getLevel().getBlockState(helper.absolutePos(portPos));
            IInWorldGridNodeHost gridHost = helper.getLevel().getCapability(
                    AECapabilities.IN_WORLD_GRID_NODE_HOST, helper.absolutePos(portPos), null);
            helper.assertTrue(gridHost == port,
                    "AE2 IN_WORLD_GRID_NODE_HOST capability is exposed for stocking");
            helper.assertTrue(helper.getLevel().getCapability(AECapabilities.GENERIC_INTERNAL_INV,
                            helper.absolutePos(portPos), portState, port, Direction.NORTH) == null,
                    "Stocking interface does not expose GENERIC_INTERNAL_INV");
            helper.assertTrue(helper.getLevel().getCapability(AECapabilities.ME_STORAGE,
                            helper.absolutePos(portPos), portState, port, Direction.NORTH) == null,
                    "Stocking interface does not expose ME_STORAGE");

            CapabilitySnapshot snapshot = port.capabilitySnapshot();
            helper.assertTrue(snapshot.capabilities().size() == 2,
                    "Stocking interface exposes item and fluid MMCR capabilities");
            for (MachineCapability capability : snapshot.capabilities()) {
                helper.assertTrue(capability.type().id().equals(PortFamilyIds.ITEM)
                                || capability.type().id().equals(PortFamilyIds.FLUID),
                        "Stocking capability has an item or fluid family");
                helper.assertTrue(capability.facet(TransferFacet.class).isEmpty(),
                        "Stocking capability does not expose TransferFacet");
            }
            MachineCapability itemCapability = snapshot.capabilities().stream()
                    .filter(capability -> capability.type().id().equals(PortFamilyIds.ITEM))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Stocking item capability is missing"));
            MachineCapability fluidCapability = snapshot.capabilities().stream()
                    .filter(capability -> capability.type().id().equals(PortFamilyIds.FLUID))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Stocking fluid capability is missing"));
            helper.assertTrue(itemCapability.type().id().equals(PortFamilyIds.ITEM),
                    "Stocking item capability is explicitly bound to the item family");
            helper.assertTrue(fluidCapability.type().id().equals(PortFamilyIds.FLUID),
                    "Stocking fluid capability is explicitly bound to the fluid family");
            helper.assertTrue(port.getInterfaceLogic().getConfig().getAmount(0) == 1L,
                    "Stocking item marker is normalized to one resource");
            helper.assertTrue(port.getInterfaceLogic().getConfig().getAmount(1) == 1L,
                    "Stocking fluid marker is normalized to one resource");
            helper.assertTrue(port.getInterfaceLogic().getConfig().getKey(0)
                            .equals(AEItemKey.of(Items.IRON_INGOT)),
                    "Stocking item marker key is iron ingot");
            helper.assertTrue(port.getInterfaceLogic().getConfig().getKey(1)
                            .equals(AEFluidKey.of(Fluids.WATER)),
                    "Stocking fluid marker key is water");
            helper.assertTrue(port.getInterfaceLogic().getStorage().getStack(0).amount() == ITEM_AMOUNT,
                    "Item display mirror follows the watcher amount");
            helper.assertTrue(port.getInterfaceLogic().getStorage().getStack(1).amount() == FLUID_AMOUNT,
                    "Fluid display mirror follows the watcher amount");

            CapabilityRequests.ResourceRequest<ItemResource> itemRequest = new CapabilityRequests.ResourceRequest<>(
                    itemCapability.type(), IOType.INPUT, 1L,
                    List.of(new CapabilityRequests.ResourceAction<>(
                            0, ItemResource.of(Items.IRON_INGOT), 3L, false)));
            CapabilityRequests.ResourceRequest<FluidResource> fluidRequest = new CapabilityRequests.ResourceRequest<>(
                    fluidCapability.type(), IOType.INPUT, 1L,
                    List.of(new CapabilityRequests.ResourceAction<>(
                            1, FluidResource.of(Fluids.WATER), 1_000L, false)));
            CapabilityOperation itemOperation = itemCapability.prepare(itemRequest);
            CapabilityOperation fluidOperation = fluidCapability.prepare(fluidRequest);
            MEStorage itemNetwork = itemChest.getInventory();
            MEStorage fluidNetwork = fluidChest.getInventory();
            try (Transaction transaction = Transaction.openRoot()) {
                CapabilityResult itemResult = itemOperation.commit(transaction);
                CapabilityResult fluidResult = fluidOperation.commit(transaction);
                helper.assertTrue(itemResult.success(),
                        "Stocking item capability operation commits successfully");
                helper.assertTrue(fluidResult.success(),
                        "Stocking fluid capability operation commits successfully");
                helper.assertTrue(itemNetwork.extract(AEItemKey.of(Items.IRON_INGOT), ITEM_AMOUNT,
                                Actionable.SIMULATE, IActionSource.empty()) == ITEM_AMOUNT,
                        "Item MEStorage is unchanged before the root transaction commits");
                helper.assertTrue(fluidNetwork.extract(AEFluidKey.of(Fluids.WATER), FLUID_AMOUNT,
                                Actionable.SIMULATE, IActionSource.empty()) == FLUID_AMOUNT,
                        "Fluid MEStorage is unchanged before the root transaction commits");
                transaction.commit();
                helper.assertTrue(itemNetwork.extract(AEItemKey.of(Items.IRON_INGOT), ITEM_AMOUNT,
                                Actionable.SIMULATE, IActionSource.empty()) == ITEM_AMOUNT - 3L,
                        "Item MEStorage quantity is deducted after transaction commit");
                helper.assertTrue(fluidNetwork.extract(AEFluidKey.of(Fluids.WATER), FLUID_AMOUNT,
                                Actionable.SIMULATE, IActionSource.empty()) == FLUID_AMOUNT - 1_000L,
                        "Fluid MEStorage quantity is deducted after transaction commit");
            }
        });

        helper.runAtTickTime(12, () -> {
            helper.assertTrue(port.getInterfaceLogic().getStorage().getStack(0).amount() == ITEM_AMOUNT - 3L,
                    "Item watcher display updates on the next tick without a full scan");
            helper.assertTrue(port.getInterfaceLogic().getStorage().getStack(1).amount() == FLUID_AMOUNT - 1_000L,
                    "Fluid watcher display updates on the next tick without a full scan");
            helper.succeed();
        });
    }
}
