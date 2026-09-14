package cn.howxu.mmcr;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.blockentity.storage.MEChestBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternProviderLogic;
import cn.howxu.mmcr.compat.appliedenergistics2.AE2Bridge;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.PatternInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end GameTest coverage for the AE2 pattern interface native logic lifecycle.
 *
 * @author howxu <dev@howxu.cn>
 */
public class AE2PatternInterfaceGameTest {
    private static final BlockPos PORT_POS = new BlockPos(0, 0, 0);
    private static final BlockPos RESTORED_PORT_POS = new BlockPos(10, 0, 0);
    private static final BlockPos TARGET_CHEST_POS = new BlockPos(1, 0, 0);
    private static final BlockPos ME_CHEST_POS = new BlockPos(3, 0, 0);
    private static final BlockPos ENERGY_POS = new BlockPos(3, 0, 2);
    private static final BlockPos REDSTONE_POS = new BlockPos(-1, 0, 0);
    private static final BlockPos CONTROLLER_POS = new BlockPos(0, 0, 4);

    public void patternInterfaceRestoresPatternsAndWakesNativeWork(GameTestHelper helper) {
        AtomicReference<PatternInterfaceBlockEntity> restoredHost = new AtomicReference<>();
        AtomicLong returnDrainAvailabilityEpoch = new AtomicLong();
        helper.assertTrue(AE2Bridge.get().available(), "AE2 must be loaded for this integration test");
        helper.setBlock(PORT_POS, ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get().defaultBlockState());
        helper.setBlock(RESTORED_PORT_POS, ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get().defaultBlockState());
        helper.setBlock(TARGET_CHEST_POS, Blocks.CHEST.defaultBlockState());
        helper.setBlock(ME_CHEST_POS, AEBlocks.ME_CHEST.block().defaultBlockState());
        helper.setBlock(ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        helper.setBlock(CONTROLLER_POS, ModBlocks.controllerFor(MMCR.id("test_cube")).get().defaultBlockState());

        helper.runAtTickTime(3, () -> {
            PatternInterfaceBlockEntity host = host(helper);
            host.linkControllerAppearance(controller(helper).getBlockPos(), null);
            var pattern = PatternDetailsHelper.encodeProcessingPattern(
                    List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L)),
                    List.of(new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 1L)));
            host.getLogic().getPatternInv().addItems(pattern);

            CompoundTag saved = save(host, helper);
            PatternInterfaceBlockEntity restored = PatternInterfaceKind.INSTANCE.entityFactory().create(
                    helper.absolutePos(RESTORED_PORT_POS),
                    ModBlocks.BLOCKS.get("ae2_me_pattern_interface").get().defaultBlockState());
            restored.setLevel(helper.getLevel());
            restoredHost.set(restored);
            load(restored, helper, saved);
            helper.assertTrue(restored.getLogic().getAvailablePatterns().isEmpty(),
                    "Native read restores pattern inventory before ready-time pattern rebuilding");
            restored.clearRemoved();
        });

        helper.runAtTickTime(4, () -> {
            PatternInterfaceBlockEntity host = host(helper);
            helper.assertTrue(restoredHost.get().getLogic().getAvailablePatterns().size() == 1,
                    "Ready-time native rebuilding advertises the restored pattern");
            setUnlockEvent(host.getLogic(), "REDSTONE_POWER");
            helper.setBlock(REDSTONE_POS, Blocks.REDSTONE_BLOCK.defaultBlockState());
        });

        helper.runAtTickTime(5, () -> {
            PatternInterfaceBlockEntity host = host(helper);
            helper.assertTrue(unlockEventName(host.getLogic()) == null,
                    "Neighbor power forwards to native redstone craft-lock handling");

            setUnlockEvent(host.getLogic(), "REDSTONE_PULSE");
            host.getLogic().updateRedstoneState();
            helper.setBlock(REDSTONE_POS, Blocks.AIR.defaultBlockState());
        });

        helper.runAtTickTime(6, () -> {
            PatternInterfaceBlockEntity host = host(helper);
            helper.assertTrue("REDSTONE_POWER".equals(unlockEventName(host.getLogic())),
                    "Neighbor pulse loss advances the native craft lock to re-power waiting");
            helper.setBlock(REDSTONE_POS, Blocks.REDSTONE_BLOCK.defaultBlockState());
        });

        helper.runAtTickTime(7, () -> {
            PatternInterfaceBlockEntity host = host(helper);
            helper.assertTrue(unlockEventName(host.getLogic()) == null,
                    "Neighbor pulse re-power unlocks the native craft lock");
            helper.getLevel().destroyBlock(helper.absolutePos(ME_CHEST_POS), true);
            helper.getLevel().destroyBlock(helper.absolutePos(ENERGY_POS), true);
        });

        helper.runAtTickTime(8, () -> {
            PatternInterfaceBlockEntity host = host(helper);
            host.getLogic().getReturnInv().setStack(0, new GenericStack(AEItemKey.of(Items.GOLD_INGOT), 2L));
            returnDrainAvailabilityEpoch.set(controller(helper).resourceAvailabilityEpoch());
            setPendingSend(host.getLogic(), AEItemKey.of(Items.IRON_INGOT), 3L);
            helper.assertTrue(host.getLogic().isBusy(), "Native send list is pending while disconnected");
            helper.setBlock(ME_CHEST_POS, AEBlocks.ME_CHEST.block().defaultBlockState());
            helper.setBlock(ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
        });

        helper.runAtTickTime(10, () -> connectNetwork(helper));

        helper.runAtTickTime(31, () -> {
            MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS, MEChestBlockEntity.class);
            ChestBlockEntity chest = helper.getBlockEntity(TARGET_CHEST_POS, ChestBlockEntity.class);
            helper.assertTrue(meChest.getInventory().extract(AEItemKey.of(Items.GOLD_INGOT), 2L,
                            Actionable.SIMULATE, appeng.api.networking.security.IActionSource.empty()) == 2L,
                    "Reconnect wakes return inventory injection without a custom ticker");
            helper.assertTrue(itemCount(chest, Items.IRON_INGOT) == 3L,
                    "Reconnect wakes native pending sends through PatternProviderLogic.isBusy()");
            helper.assertTrue(!host(helper).getLogic().isBusy(), "Native pending send list drains after reconnect");
            helper.assertTrue(controller(helper).resourceAvailabilityEpoch() > returnDrainAvailabilityEpoch.get(),
                    "Native return inventory drain wakes linked output-capacity searches");
            helper.succeed();
        });
    }

    private static void connectNetwork(GameTestHelper helper) {
        PatternInterfaceBlockEntity host = host(helper);
        MEChestBlockEntity meChest = helper.getBlockEntity(ME_CHEST_POS, MEChestBlockEntity.class);
        CreativeEnergyCellBlockEntity energy = helper.getBlockEntity(ENERGY_POS, CreativeEnergyCellBlockEntity.class);
        meChest.setCell(AEItems.ITEM_CELL_1K.stack());
        helper.assertTrue(host.getMainNode().getNode() != null, "Pattern interface grid node is initialized");
        helper.assertTrue(meChest.getMainNode().getNode() != null, "ME chest grid node is initialized");
        helper.assertTrue(energy.getMainNode().getNode() != null, "Energy cell grid node is initialized");
        GridHelper.createConnection(host.getMainNode().getNode(), meChest.getMainNode().getNode());
        GridHelper.createConnection(host.getMainNode().getNode(), energy.getMainNode().getNode());
    }

    private static PatternInterfaceBlockEntity host(GameTestHelper helper) {
        PatternInterfaceBlockEntity host = helper.getBlockEntity(PORT_POS, PatternInterfaceBlockEntity.class);
        if (host == null) throw new AssertionError("Pattern interface block entity was not created");
        return host;
    }

    private static MachineControllerBlockEntity controller(GameTestHelper helper) {
        MachineControllerBlockEntity controller = helper.getBlockEntity(CONTROLLER_POS, MachineControllerBlockEntity.class);
        if (controller == null) throw new AssertionError("Controller block entity was not created");
        return controller;
    }

    private static long itemCount(ChestBlockEntity chest, Item item) {
        long count = 0L;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (chest.getItem(slot).is(item)) count += chest.getItem(slot).getCount();
        }
        return count;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setUnlockEvent(PatternProviderLogic logic, String value) {
        try {
            Field field = PatternProviderLogic.class.getDeclaredField("unlockEvent");
            field.setAccessible(true);
            field.set(logic, Enum.valueOf((Class<? extends Enum>) field.getType(), value));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to set native pattern-provider craft-lock state", exception);
        }
    }

    private static String unlockEventName(PatternProviderLogic logic) {
        try {
            Field field = PatternProviderLogic.class.getDeclaredField("unlockEvent");
            field.setAccessible(true);
            Object value = field.get(logic);
            return value instanceof Enum<?> event ? event.name() : null;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to read native pattern-provider craft-lock state", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setPendingSend(PatternProviderLogic logic, AEItemKey key, long amount) {
        try {
            Field sendList = PatternProviderLogic.class.getDeclaredField("sendList");
            sendList.setAccessible(true);
            ((List<GenericStack>) sendList.get(logic)).add(new GenericStack(key, amount));
            Field sendDirection = PatternProviderLogic.class.getDeclaredField("sendDirection");
            sendDirection.setAccessible(true);
            sendDirection.set(logic, net.minecraft.core.Direction.EAST);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to set native pattern-provider pending send", exception);
        }
    }

    private static CompoundTag save(BlockEntity entity, GameTestHelper helper) {
        try {
            Method save = BlockEntity.class.getDeclaredMethod("saveAdditional", ValueOutput.class);
            save.setAccessible(true);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess());
            save.invoke(entity, output);
            return output.buildResult();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to save block entity " + entity, exception);
        }
    }

    private static void load(BlockEntity entity, GameTestHelper helper, CompoundTag data) {
        try {
            Method load = BlockEntity.class.getDeclaredMethod("loadAdditional", ValueInput.class);
            load.setAccessible(true);
            load.invoke(entity, TagValueInput.create(ProblemReporter.DISCARDING,
                    helper.getLevel().registryAccess(), data));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to reload block entity " + entity, exception);
        }
    }
}
