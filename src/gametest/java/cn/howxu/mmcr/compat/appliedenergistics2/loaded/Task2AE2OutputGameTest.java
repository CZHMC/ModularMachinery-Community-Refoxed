package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.me.service.TickManagerService;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.registry.PortKinds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minimal world-level coverage for the Task 2 AE2 output lifecycle and wake-up.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class Task2AE2OutputGameTest {
    public void outputWakeUpAndActiveNodeLifecycle(GameTestHelper helper) {
        BlockPos outputPos = new BlockPos(0, 1, 0);
        BlockPos chestPos = new BlockPos(3, 1, 0);
        BlockPos energyPos = new BlockPos(3, 1, 2);
        AE2OutputInterfaceBlockEntity output = placeOutput(helper, outputPos);
        helper.setBlock(chestPos, AEBlocks.ME_CHEST.block().defaultBlockState());
        helper.setBlock(energyPos, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        MEChestBlockEntity chest = helper.getBlockEntity(chestPos, MEChestBlockEntity.class);
        CreativeEnergyCellBlockEntity energy = helper.getBlockEntity(energyPos,
                CreativeEnergyCellBlockEntity.class);
        chest.setCell(AEItems.ITEM_CELL_1K.stack());

        helper.runAtTickTime(2, () -> {
            IGridNode outputNode = output.getMainNode().getNode();
            helper.assertTrue(outputNode != null, "Output interface created its grid node before connection");
            helper.assertTrue(chest.getMainNode().getNode() != null, "ME Chest created its grid node");
            helper.assertTrue(energy.getMainNode().getNode() != null, "Creative energy cell created its grid node");
        });

        helper.runAtTickTime(3, () -> {
            output.getStorage().setStack(0,
                    new GenericStack(AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT), 8L));
            IGridNode outputNode = output.getMainNode().getNode();
            GridHelper.createConnection(outputNode, chest.getMainNode().getNode());
            GridHelper.createConnection(outputNode, energy.getMainNode().getNode());
            TickManagerService tickManager = (TickManagerService) outputNode.getGrid().getTickManager();
            MMCR.LOG.info("Task2 output tick 3: cache={}, active={}, status={}",
                    output.getStorage().getStack(0), outputNode.isActive(), tickManager.getStatus(outputNode));
        });

        helper.runAtTickTime(12, () -> {
            IGridNode activeNode = output.getMainNode().getNode();
            TickManagerService tickManager = (TickManagerService) activeNode.getGrid().getTickManager();
            MEStorage network = activeNode.getGrid().getStorageService().getInventory();
            MMCR.LOG.info("Task2 output tick 12: cache={}, active={}, status={}, gridExtract={}, chestExtract={}, "
                            + "gridInsert={}", output.getStorage().getStack(0), activeNode.isActive(),
                    tickManager.getStatus(activeNode), network.extract(AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT),
                            8L, Actionable.SIMULATE, IActionSource.empty()),
                    chest.getInventory().extract(AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT), 8L,
                            Actionable.SIMULATE, IActionSource.empty()),
                    network.insert(AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT), 8L,
                            Actionable.SIMULATE, IActionSource.empty()));
            helper.assertTrue(activeNode != null && activeNode.isActive(),
                    "Output interface is active before lifecycle teardown");
            helper.assertTrue(chest.getInventory().extract(AEItemKey.of(net.minecraft.world.item.Items.IRON_INGOT),
                            8L, Actionable.SIMULATE, appeng.api.networking.security.IActionSource.empty()) == 8L,
                    "Network wake-up flushes the cached output into the ME Chest");
            helper.assertTrue(output.getStorage().isEmpty(), "Output cache is empty after the network wake-up flush");

            output.onChunkUnloaded();
            helper.assertTrue(output.getMainNode().getNode() == null,
                    "Unloading destroys the already active output node");
            output.clearRemoved();
        });

        helper.runAtTickTime(14, () -> {
            IGridNode recreated = output.getMainNode().getNode();
            helper.assertTrue(recreated != null, "Clearing removal recreates the output node on its first tick");
            GridHelper.createConnection(recreated, chest.getMainNode().getNode());
            GridHelper.createConnection(recreated, energy.getMainNode().getNode());
        });

        helper.runAtTickTime(24, () -> {
            IGridNode activeNode = output.getMainNode().getNode();
            helper.assertTrue(activeNode != null && activeNode.isActive(),
                    "Recreated output node becomes active before removal");
            output.setRemoved();
            helper.assertTrue(output.isRemoved(), "Removing the output host marks it removed");
            helper.assertTrue(output.getMainNode().getNode() == null,
                    "Removing destroys the active output node");
            helper.succeed();
        });
    }

    private static AE2OutputInterfaceBlockEntity placeOutput(GameTestHelper helper, BlockPos pos) {
        BlockState state = ModBlocks.BLOCKS.get(PortKinds.ITEM_OUTPUT.id()).get().defaultBlockState();
        helper.setBlock(pos, state);
        helper.getLevel().removeBlockEntity(helper.absolutePos(pos));
        AE2OutputInterfaceBlockEntity output = new AE2OutputInterfaceBlockEntity(
                helper.absolutePos(pos), state, PortKinds.ITEM_OUTPUT);
        helper.getLevel().setBlockEntity(output);
        return output;
    }
}
