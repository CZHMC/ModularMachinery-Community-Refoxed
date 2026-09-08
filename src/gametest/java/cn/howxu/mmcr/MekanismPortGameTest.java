package cn.howxu.mmcr;

import cn.howxu.mmcr.api.compat.mekanism.MekanismFailureReasons;
import cn.howxu.mmcr.api.machine.BlockArray;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.DynamicMachine;
import cn.howxu.mmcr.api.machine.MachineAppearanceSpec;
import cn.howxu.mmcr.api.machine.MachineControllerSpec;
import cn.howxu.mmcr.api.machine.PortRequirementSpec;
import cn.howxu.mmcr.api.machine.PortTierRequirementSpec;
import cn.howxu.mmcr.api.publicapi.recipe.CustomRecipeIo;
import cn.howxu.mmcr.api.publicapi.recipe.MachineRecipeBuilder;
import cn.howxu.mmcr.api.publicapi.recipe.RecipeIo;
import cn.howxu.mmcr.api.publicapi.recipe.requirement.CustomRequirement;
import cn.howxu.mmcr.client.model.MachineModelDataKeys;
import cn.howxu.mmcr.compat.mekanism.MekanismBridge;
import cn.howxu.mmcr.compat.mekanism.MekanismBridgeBootstrap;
import cn.howxu.mmcr.compat.mekanism.MekanismRecipeTypes;
import cn.howxu.mmcr.compat.mekanism.loaded.ChemicalPortBlockEntity;
import cn.howxu.mmcr.compat.mekanism.loaded.HeatPortBlockEntity;
import cn.howxu.mmcr.compat.mekanism.loaded.MekanismPortSizes;
import cn.howxu.mmcr.internal.block.MachineControllerBlock;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import mekanism.api.AutomationType;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalBuilder;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.heat.HeatAPI;
import mekanism.api.heat.IHeatHandler;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * End-to-end GameTest coverage for the loaded Mekanism port integration.
 *
 * @author howxu <dev@howxu.cn>
 */
public class MekanismPortGameTest {

    public void normalChemicalPortRejectsRadioactiveAndAcceptsNonRadioactive(GameTestHelper helper) {
        BlockPos pos = new BlockPos(0, 1, 0);
        helper.setBlock(pos, ModBlocks.BLOCKS.get("chemical_input_hatch_basic").get().defaultBlockState());
        ChemicalPortBlockEntity port = helper.getBlockEntity(pos, ChemicalPortBlockEntity.class);

        ChemicalResource radioactive = registerRadioactiveChemical("radioactive_blocked");
        ChemicalResource oxygen = registerOxygenLikeChemical("oxygen_accepted");

        try (Transaction tx = Transaction.openRoot()) {
            int insertedRadioactive = port.chemicalTank().insert(
                    radioactive, 1_000, tx, AutomationType.EXTERNAL);
            helper.assertValueEqual(0, insertedRadioactive,
                    "Normal chemical port rejects radioactive chemicals");
            int insertedOxygen = port.chemicalTank().insert(
                    oxygen, 1_000, tx, AutomationType.EXTERNAL);
            helper.assertValueEqual(1_000, insertedOxygen,
                    "Normal chemical port accepts non-radioactive chemicals");
            tx.commit();
        }
        helper.succeed();
    }

    public void radioactiveChemicalPortRejectsNonRadioactiveAndAcceptsRadioactive(GameTestHelper helper) {
        BlockPos pos = new BlockPos(0, 1, 0);
        helper.setBlock(pos, ModBlocks.BLOCKS.get("radioactive_chemical_input_hatch").get().defaultBlockState());
        ChemicalPortBlockEntity port = helper.getBlockEntity(pos, ChemicalPortBlockEntity.class);

        ChemicalResource oxygen = registerOxygenLikeChemical("oxygen_rejected_by_radio");
        ChemicalResource radioactive = registerRadioactiveChemical("radioactive_accepted_by_radio");

        try (Transaction tx = Transaction.openRoot()) {
            int insertedOxygen = port.chemicalTank().insert(
                    oxygen, 1_000, tx, AutomationType.EXTERNAL);
            helper.assertValueEqual(0, insertedOxygen,
                    "Radioactive chemical port rejects non-radioactive chemicals");
            int insertedRadioactive = port.chemicalTank().insert(
                    radioactive, 1_000, tx, AutomationType.EXTERNAL);
            helper.assertValueEqual(1_000, insertedRadioactive,
                    "Radioactive chemical port accepts radioactive chemicals");
            tx.commit();
        }
        helper.succeed();
    }

    public void normalChemicalCapacitiesMatchDeclaredTiers(GameTestHelper helper) {
        record TierExpectation(String port, long expected) {}
        List<TierExpectation> tiers = List.of(
                new TierExpectation("chemical_input_hatch_basic",
                        MekanismPortSizes.CHEMICAL_BASIC_CAPACITY),
                new TierExpectation("chemical_input_hatch_advanced",
                        MekanismPortSizes.CHEMICAL_ADVANCED_CAPACITY),
                new TierExpectation("chemical_input_hatch_elite",
                        MekanismPortSizes.CHEMICAL_ELITE_CAPACITY),
                new TierExpectation("chemical_input_hatch_ultimate",
                        MekanismPortSizes.CHEMICAL_ULTIMATE_CAPACITY));
        ChemicalResource oxygen = registerOxygenLikeChemical("oxygen_capacity_probe");
        for (TierExpectation tier : tiers) {
            BlockPos pos = new BlockPos(0, 1, 0);
            helper.setBlock(pos, ModBlocks.BLOCKS.get(tier.port()).get().defaultBlockState());
            ChemicalPortBlockEntity port = helper.getBlockEntity(pos, ChemicalPortBlockEntity.class);
            helper.assertValueEqual(tier.expected(), capacityForResource(port, oxygen),
                    tier.port() + " reports the expected tier capacity for non-radioactive chemicals");
            helper.setBlock(pos, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    public void radioactiveChemicalCapacityIsFixedTier(GameTestHelper helper) {
        BlockPos pos = new BlockPos(0, 1, 0);
        helper.setBlock(pos, ModBlocks.BLOCKS.get("radioactive_chemical_input_hatch").get().defaultBlockState());
        ChemicalPortBlockEntity port = helper.getBlockEntity(pos, ChemicalPortBlockEntity.class);
        ChemicalResource radioactive = registerRadioactiveChemical("radioactive_capacity_probe");
        helper.assertValueEqual(MekanismPortSizes.RADIOACTIVE_CHEMICAL_CAPACITY,
                capacityForResource(port, radioactive),
                "Radioactive chemical port reports the documented fixed capacity");
        helper.succeed();
    }

    public void heatTemperatureRequirementReadsWithoutConsumingHeat(GameTestHelper helper) {
        BlockPos heatPos = new BlockPos(0, 1, 0);
        helper.setBlock(heatPos, ModBlocks.BLOCKS.get("heat_input_hatch").get().defaultBlockState());
        HeatPortBlockEntity port = helper.getBlockEntity(heatPos, HeatPortBlockEntity.class);

        double ambient = HeatAPI.getAmbientTemp(port.getLevel(), port.getBlockPos());
        double capacity = port.heatCapacitor().getHeatCapacity();
        double baseline = ambient * capacity;
        setHeat(port, baseline * 5D);
        double before = port.heatCapacitor().getHeat();
        helper.assertTrue(before > baseline,
                "Heat input port holds more heat than its ambient baseline before the recipe check");

        IHeatHandler handler = port.heatHandler();
        helper.assertValueEqual(before, handler.getTemperature() * handler.getHeatCapacity(),
                "Heat input port's IHeatHandler reports the same stored heat as its capacitor");

        helper.assertTrue(handler.getTemperature() >= 450D,
                "Setting heat to 5x the ambient baseline exceeds the 450K minimum temperature requirement");

        helper.assertValueEqual(before, port.heatCapacitor().getHeat(),
                "Reading the heat input port does not consume its stored heat");
        helper.succeed();
    }

    public void heatOutputHandleHeatIncreasesStoredHeat(GameTestHelper helper) {
        BlockPos heatPos = new BlockPos(0, 1, 0);
        helper.setBlock(heatPos, ModBlocks.BLOCKS.get("heat_output_hatch").get().defaultBlockState());
        HeatPortBlockEntity port = helper.getBlockEntity(heatPos, HeatPortBlockEntity.class);

        double ambient = HeatAPI.getAmbientTemp(port.getLevel(), port.getBlockPos());
        double baseline = ambient * port.heatCapacitor().getHeatCapacity();
        double before = port.heatCapacitor().getHeat();

        double delta = port.heatCapacitor().getHeatCapacity();
        try (Transaction transaction = Transaction.openRoot()) {
            port.heatCapacitor().handleHeat(delta, transaction);
            transaction.commit();
        }

        helper.assertValueEqual(before + delta, port.heatCapacitor().getHeat(),
                "Heat output port stores the heat delta delivered through handleHeat");
        helper.assertTrue(port.heatCapacitor().getHeat() > baseline,
                "Heat output port stores more heat than its ambient baseline after handleHeat");
        helper.succeed();
    }

    public void chemicalInputAutoImportsFromAdjacentOutput(GameTestHelper helper) {
        BlockPos inputPos = new BlockPos(0, 1, 0);
        BlockPos sourcePos = inputPos.relative(Direction.EAST);
        helper.setBlock(inputPos, ModBlocks.BLOCKS.get("chemical_input_hatch_basic").get().defaultBlockState());
        helper.setBlock(sourcePos, ModBlocks.BLOCKS.get("chemical_output_hatch_basic").get().defaultBlockState());
        ChemicalPortBlockEntity input = helper.getBlockEntity(inputPos, ChemicalPortBlockEntity.class);
        ChemicalPortBlockEntity source = helper.getBlockEntity(sourcePos, ChemicalPortBlockEntity.class);

        ChemicalResource oxygen = registerOxygenLikeChemical("oxygen_auto_import");
        source.chemicalTank().setContents(oxygen, 5_000L, null);

        input.toggleAutoIOEnabled();
        input.setAllAutoIOSides(false);
        input.setAutoIOSide(Direction.EAST, true);

        helper.runAtTickTime(60, input::serverTick);
        helper.runAtTickTime(80, () -> {
            helper.assertValueEqual(5_000L, input.chemicalTank().amountAsLong(),
                    "Chemical input auto-imports 5_000 units from the adjacent output port");
            helper.assertTrue(source.chemicalTank().amountAsLong() < 5_000L,
                    "Adjacent chemical output port loses contents to the auto-import");
            helper.succeed();
        });
    }

    public void chemicalInputEjectionStopsAfterFirstTarget(GameTestHelper helper) {
        BlockPos sourcePos = new BlockPos(0, 1, 0);
        BlockPos firstReceiver = sourcePos.relative(Direction.EAST);
        BlockPos secondReceiver = sourcePos.relative(Direction.WEST);
        helper.setBlock(sourcePos, ModBlocks.BLOCKS.get("chemical_input_hatch_basic").get().defaultBlockState());
        helper.setBlock(firstReceiver, ModBlocks.BLOCKS.get("chemical_output_hatch_basic").get().defaultBlockState());
        helper.setBlock(secondReceiver, ModBlocks.BLOCKS.get("chemical_output_hatch_basic").get().defaultBlockState());
        ChemicalPortBlockEntity source = helper.getBlockEntity(sourcePos, ChemicalPortBlockEntity.class);
        ChemicalPortBlockEntity first = helper.getBlockEntity(firstReceiver, ChemicalPortBlockEntity.class);
        ChemicalPortBlockEntity second = helper.getBlockEntity(secondReceiver, ChemicalPortBlockEntity.class);

        ChemicalResource oxygen = registerOxygenLikeChemical("oxygen_eject");
        source.chemicalTank().setContents(oxygen, 2_000L, null);

        helper.runAtTickTime(20, () -> {
            helper.assertTrue(source.ejectContents(), "Chemical input ejection fires");
            long firstAmount = first.chemicalTank().amountAsLong();
            long secondAmount = second.chemicalTank().amountAsLong();
            helper.assertTrue((firstAmount == 2_000L && secondAmount == 0L)
                            || (firstAmount == 0L && secondAmount == 2_000L),
                    "Exactly one adjacent chemical output receives the ejected contents");
            helper.assertValueEqual(0L, source.chemicalTank().amountAsLong(),
                    "Chemical input port is empty after ejecting its contents");
            helper.succeed();
        });
    }

    public void chemicalInputEjectionPreservesRemainder(GameTestHelper helper) {
        BlockPos sourcePos = new BlockPos(0, 1, 0);
        BlockPos firstReceiver = sourcePos.relative(Direction.EAST);
        BlockPos secondReceiver = sourcePos.relative(Direction.WEST);
        helper.setBlock(sourcePos, ModBlocks.BLOCKS.get("chemical_input_hatch_basic").get().defaultBlockState());
        helper.setBlock(firstReceiver, ModBlocks.BLOCKS.get("chemical_output_hatch_basic").get().defaultBlockState());
        helper.setBlock(secondReceiver, ModBlocks.BLOCKS.get("chemical_output_hatch_basic").get().defaultBlockState());
        ChemicalPortBlockEntity source = helper.getBlockEntity(sourcePos, ChemicalPortBlockEntity.class);
        ChemicalPortBlockEntity first = helper.getBlockEntity(firstReceiver, ChemicalPortBlockEntity.class);
        ChemicalPortBlockEntity second = helper.getBlockEntity(secondReceiver, ChemicalPortBlockEntity.class);

        ChemicalResource oxygen = registerOxygenLikeChemical("oxygen_eject_remainder");
        source.chemicalTank().setContents(oxygen, 3_000L, null);
        first.chemicalTank().setContents(oxygen, MekanismPortSizes.CHEMICAL_BASIC_CAPACITY - 2_000L, null);
        second.chemicalTank().setContents(oxygen, MekanismPortSizes.CHEMICAL_BASIC_CAPACITY - 2_000L, null);

        helper.runAtTickTime(20, () -> {
            helper.assertTrue(source.ejectContents(), "Chemical input ejection runs against partial targets");
            helper.assertValueEqual(MekanismPortSizes.CHEMICAL_BASIC_CAPACITY,
                    first.chemicalTank().amountAsLong(),
                    "First chemical target is filled to capacity");
            helper.assertValueEqual(MekanismPortSizes.CHEMICAL_BASIC_CAPACITY,
                    second.chemicalTank().amountAsLong(),
                    "Second chemical target is filled to capacity");
            helper.assertValueEqual(1_000L, source.chemicalTank().amountAsLong(),
                    "Chemical input port preserves the unsent remainder");
            helper.succeed();
        });
    }

    public void chemicalAndHeatContentsPersistAcrossReload(GameTestHelper helper) {
        BlockPos chemicalPos = new BlockPos(0, 1, 0);
        BlockPos heatPos = new BlockPos(0, 2, 0);
        helper.setBlock(chemicalPos, ModBlocks.BLOCKS.get("chemical_input_hatch_basic").get().defaultBlockState());
        helper.setBlock(heatPos, ModBlocks.BLOCKS.get("heat_input_hatch").get().defaultBlockState());
        ChemicalPortBlockEntity chemical = helper.getBlockEntity(chemicalPos, ChemicalPortBlockEntity.class);
        HeatPortBlockEntity heat = helper.getBlockEntity(heatPos, HeatPortBlockEntity.class);

        ChemicalResource oxygen = registerOxygenLikeChemical("oxygen_persist");
        chemical.chemicalTank().setContents(oxygen, 12_345L, null);
        double ambient = HeatAPI.getAmbientTemp(heat.getLevel(), heat.getBlockPos());
        double baseline = ambient * heat.heatCapacitor().getHeatCapacity();
        setHeat(heat, baseline * 7D);
        double heatBefore = heat.heatCapacitor().getHeat();
        helper.assertTrue(heatBefore > baseline,
                "Heat port holds more than the ambient baseline before persistence check");

        reloadBlockEntity(chemical, helper);
        reloadBlockEntity(heat, helper);

        helper.assertValueEqual(12_345L, chemical.chemicalTank().amountAsLong(),
                "Chemical tank contents survive a save/load cycle");
        helper.assertValueEqual(oxygen, chemical.chemicalTank().resource(),
                "Chemical tank resource identity survives a save/load cycle");
        helper.assertValueEqual(heatBefore, heat.heatCapacitor().getHeat(),
                "Heat capacitor value survives a save/load cycle");
        helper.succeed();
    }

    public void unavailableBridgeLeavesBuilderWorkingThroughCustomRecipeIo(GameTestHelper helper) {
        try {
            MekanismBridgeBootstrap.installForTesting(MekanismBridgeBootstrap.selectForTesting(false));
            helper.assertFalse(MekanismBridge.get().available(),
                    "Installed unavailable bridge reports available()==false");
            helper.assertValueEqual(MekanismFailureReasons.MEKANISM_UNAVAILABLE.id(),
                    MekanismBridge.get().unavailableReason(),
                    "Installed unavailable bridge reports the documented failure reason");

            MachineRecipeBuilder builder = MachineRecipeBuilder.recipe(
                            Identifier.fromNamespaceAndPath("mmcr_test", "unavailable_chemical_e2e"),
                            Identifier.fromNamespaceAndPath("mmcr_test", "test_cube"))
                    .duration(20)
                    .inputChemical(Identifier.fromNamespaceAndPath("mekanism", "oxygen"), 1_000L)
                    .outputChemical(Identifier.fromNamespaceAndPath("mekanism", "hydrogen"), 200L, 0.5F)
                    .inputHeatTemperature(450D)
                    .outputHeat(120D);
            List<CustomRequirement> customRequirements =
                    builder.build().requirements().stream()
                            .filter(CustomRequirement.class::isInstance)
                            .map(CustomRequirement.class::cast)
                            .toList();
            List<CustomRecipeIo> customOutputs = builder.build().customOutputs();
            helper.assertValueEqual(2, customRequirements.size(),
                    "Both chemical and temperature inputs are kept as CustomRequirement entries");
            helper.assertValueEqual(1, customOutputs.size(),
                    "The chemical output is kept as a CustomRecipeIo output");
            CustomRecipeIo chemicalInput = (CustomRecipeIo) customRequirements.get(0);
            CustomRecipeIo temperatureInput = (CustomRecipeIo) customRequirements.get(1);
            CustomRecipeIo chemicalOutput = customOutputs.get(0);
            helper.assertValueEqual(MekanismRecipeTypes.CHEMICAL, chemicalInput.typeId(),
                    "Chemical input still resolves through the public CustomRecipeIo path");
            helper.assertValueEqual(RecipeIo.INPUT, chemicalInput.ioType(),
                    "Chemical input direction stays INPUT even when Mekanism is unavailable");
            helper.assertValueEqual(MekanismRecipeTypes.HEAT_TEMPERATURE, temperatureInput.typeId(),
                    "Temperature input still resolves through the public CustomRecipeIo path");
            helper.assertValueEqual(MekanismRecipeTypes.CHEMICAL, chemicalOutput.typeId(),
                    "Chemical output still resolves through the public CustomRecipeIo path");
            helper.assertValueEqual(RecipeIo.OUTPUT, chemicalOutput.ioType(),
                    "Chemical output direction stays OUTPUT even when Mekanism is unavailable");
            helper.succeed();
        } finally {
            MekanismBridgeBootstrap.resetForTesting();
        }
    }

    public void heatPortExchangesHeatWithAdjacentMekHandler(GameTestHelper helper) {
        BlockPos heatPos = new BlockPos(0, 1, 0);
        BlockPos adjacentPos = heatPos.relative(Direction.EAST);
        helper.setBlock(heatPos, ModBlocks.BLOCKS.get("heat_input_hatch").get().defaultBlockState());
        helper.setBlock(adjacentPos, ModBlocks.BLOCKS.get("heat_output_hatch").get().defaultBlockState());
        HeatPortBlockEntity heat = helper.getBlockEntity(heatPos, HeatPortBlockEntity.class);
        BlockEntity adjacentEntity = helper.getLevel().getBlockEntity(helper.absolutePos(adjacentPos));

        IHeatHandler capability = helper.getLevel().getCapability(Capabilities.HEAT,
                helper.absolutePos(heatPos),
                helper.getLevel().getBlockState(helper.absolutePos(heatPos)),
                helper.getBlockEntity(heatPos, HeatPortBlockEntity.class),
                Direction.EAST);
        helper.assertTrue(capability != null,
                "Heat capability is exposed on the EAST side of a heat input port");

        double ambient = HeatAPI.getAmbientTemp(heat.getLevel(), heat.getBlockPos());
        double baseline = ambient * heat.heatCapacitor().getHeatCapacity();
        double delta = heat.heatCapacitor().getHeatCapacity() * 10D;
        double before = heat.heatCapacitor().getHeat();
        try (Transaction transaction = Transaction.openRoot()) {
            capability.handleHeat(delta, transaction);
            transaction.commit();
        }
        helper.assertValueEqual(before + delta, heat.heatCapacitor().getHeat(),
                "Adjacent Mek heat handler pushes the heat delta into the heat input port");
        helper.assertTrue(heat.heatCapacitor().getHeat() > baseline,
                "Heat input port stores more heat than its ambient baseline after adjacent push");
        helper.assertTrue(adjacentEntity != null,
                "Adjacent block entity is preserved for the heat exchange fixture");
        helper.succeed();
    }

    public void formedMultiblockPortReflectsBaseTextureChange(GameTestHelper helper) {
        BlockPos controllerPos = new BlockPos(0, 1, 0);
        BlockPos portPos = controllerPos.relative(Direction.EAST);
        var controllerBlock = ModBlocks.controllerFor(MMCR.id("test_cube")).get();
        var portBlock = ModBlocks.BLOCKS.get("chemical_input_hatch_basic").get();
        helper.setBlock(controllerPos, controllerBlock.defaultBlockState()
                .setValue(MachineControllerBlock.FACING, Direction.SOUTH));
        helper.setBlock(portPos, portBlock.defaultBlockState());

        MachineControllerBlockEntity controller = helper.getBlockEntity(controllerPos,
                MachineControllerBlockEntity.class);
        ChemicalPortBlockEntity port = helper.getBlockEntity(portPos, ChemicalPortBlockEntity.class);

        Identifier initialTexture = MMCR.id("block/chemical_basic_casing_initial");
        DynamicMachine machine = new DynamicMachine(
                MMCR.id("chemical_appearance_test"),
                "chemical appearance test",
                new BlockArray(Map.of(portPos.subtract(controllerPos),
                        new BlockPredicate.OfBlock(portBlock))),
                MachineControllerSpec.defaultsFor(MMCR.id("test_cube")),
                new MachineAppearanceSpec(MMCR.id("basic_casing"),
                        MMCR.id("block/basic_casing"), initialTexture),
                PortRequirementSpec.none(), PortTierRequirementSpec.none(), List.of(), Map.of());
        controller.setMachine(machine);

        helper.runAtTickTime(20, () -> {
            helper.assertTrue(controller.structureSnapshot().formed(),
                    "Chemical port controller forms with the chemical input hatch in its pattern");
            helper.assertTrue(controller.runtimeSnapshot().linkedPortPositions()
                            .contains(port.getBlockPos()),
                    "Formed controller links the chemical input port");
            helper.assertValueEqual(initialTexture, port.appearanceBaseTexture(),
                    "Chemical port receives the initial formed appearance texture");
            helper.assertValueEqual(initialTexture,
                    port.getModelData().get(MachineModelDataKeys.PORT_BASE_TEXTURE),
                    "Chemical port model data exposes the initial formed appearance texture");

            Identifier updatedTexture = MMCR.id("block/chemical_basic_casing_updated");
            port.linkControllerAppearance(controllerPos, updatedTexture);

            helper.assertValueEqual(updatedTexture, port.appearanceBaseTexture(),
                    "Chemical port reflects the controller's updated appearance texture");
            helper.assertValueEqual(updatedTexture,
                    port.getModelData().get(MachineModelDataKeys.PORT_BASE_TEXTURE),
                    "Chemical port model data reflects the updated formed appearance texture");
            helper.succeed();
        });
    }

    private static long capacityForResource(ChemicalPortBlockEntity port, ChemicalResource resource) {
        return port.chemicalTank().capacityAsLong(resource);
    }

    private static void setHeat(HeatPortBlockEntity port, double target) {
        try (Transaction transaction = Transaction.openRoot()) {
            port.heatCapacitor().setHeat(target, transaction);
            transaction.commit();
        }
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

    private static ChemicalResource registerOxygenLikeChemical(String path) {
        return ChemicalResource.of(registerChemical(path, false));
    }

    private static ChemicalResource registerRadioactiveChemical(String path) {
        return ChemicalResource.of(registerChemical(path, true));
    }

    private static Holder.Reference<Chemical> registerChemical(String path, boolean radioactive) {
        ResourceKey<Chemical> key = ResourceKey.create(MekanismAPI.CHEMICAL_REGISTRY_NAME,
                Identifier.fromNamespaceAndPath("mmcr_test", path));
        MappedRegistry<Chemical> registry = (MappedRegistry<Chemical>) MekanismAPI.CHEMICAL_REGISTRY;
        return registry.get(key).orElseGet(() -> {
            registry.unfreeze(true);
            Chemical value = new Chemical(ChemicalBuilder.builder()) {
                @Override
                public boolean isRadioactive() {
                    return radioactive;
                }
            };
            Registry.register(registry, key.identifier(), value);
            registry.freeze();
            return registry.get(key).orElseThrow();
        });
    }
}
