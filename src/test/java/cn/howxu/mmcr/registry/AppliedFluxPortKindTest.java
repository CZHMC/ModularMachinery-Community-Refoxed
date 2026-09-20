package cn.howxu.mmcr.registry;

import appeng.api.AECapabilities;
import cn.howxu.mmcr.LevelStub;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandlerSupport;
import cn.howxu.mmcr.compat.appliedflux.AppliedFluxBridge;
import cn.howxu.mmcr.compat.appliedflux.AppliedFluxBridgeBootstrap;
import cn.howxu.mmcr.compat.appliedflux.loaded.LoadedAppliedFluxBridge;
import cn.howxu.mmcr.compat.appliedflux.loaded.capability.FluxEnergyInputCapability;
import cn.howxu.mmcr.compat.appliedflux.loaded.capability.FluxEnergyOutputCapability;
import cn.howxu.mmcr.compat.appliedflux.loaded.kind.FluxEnergyInputKind;
import cn.howxu.mmcr.compat.appliedflux.loaded.kind.FluxEnergyOutputKind;
import cn.howxu.mmcr.compat.appliedflux.loaded.storage.FluxEnergyBuffer;
import cn.howxu.mmcr.compat.appliedflux.loaded.tile.FluxEnergyInputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedflux.loaded.tile.FluxEnergyOutputInterfaceBlockEntity;
import cn.howxu.mmcr.internal.event.ModCapabilities;
import cn.howxu.mmcr.internal.capability.BuiltinCapabilityDefinitions;
import cn.howxu.mmcr.internal.port.EnergyHatchSize;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.reflect.Constructor;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies AppFlux port registration and its optional capability boundary.
 *
 * @author howxu <dev@howxu.cn>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppliedFluxPortKindTest {
    private static final String INPUT_ID = "appflux_me_flux_input_interface";
    private static final String OUTPUT_ID = "appflux_me_flux_output_interface";

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @AfterEach
    void resetOptionalState() {
        PortKinds.clearForTesting();
        AppliedFluxBridgeBootstrap.resetForTesting();
    }

    @Test
    @Order(0)
    void unavailableAppliedFluxDoesNotAddKindsAndDoesNotLinkLoadedClass() {
        AppliedFluxBridge bridge = AppliedFluxBridgeBootstrap.selectForTesting(false);

        assertThat(bridge.available()).isFalse();
        assertThat(bridge.portKinds()).isEmpty();
        assertThat(bridge.isPort(INPUT_ID)).isFalse();
        assertThat(bridge.isPort(OUTPUT_ID)).isFalse();
        assertThat(PortKinds.all()).extracting(IOPortKind::id)
                .doesNotContain(INPUT_ID, OUTPUT_ID);
    }

    @Test
    @Order(1)
    void appliedFluxKindsHaveStableIdsAndAbsoluteEnergyTier() {
        AppliedFluxBridge bridge = AppliedFluxBridgeBootstrap.selectForTesting(true);

        assertThat(bridge.portKinds()).extracting(IOPortKind::id)
                .containsExactly(INPUT_ID, OUTPUT_ID);
        assertThat(bridge.portKinds()).allSatisfy(kind -> {
            assertThat(kind.modDependencies()).containsExactly("ae2", "appflux");
            assertThat(kind.families()).singleElement().satisfies(family -> {
                assertThat(family.familyId()).isEqualTo(cn.howxu.mmcr.internal.port.PortFamilyIds.ENERGY);
                assertThat(family.detectionTier()).isEqualTo(EnergyHatchSize.ULTIMATE.ordinal() + 1);
            });
            assertThat(kind.definition().bindings()).singleElement().satisfies(binding -> {
                assertThat(binding.type().id()).isEqualTo(cn.howxu.mmcr.internal.port.PortFamilyIds.ENERGY);
                assertThat(binding.nativeTransferExposure()).isFalse();
                assertThat(binding.externalExposure()).isEmpty();
            });
        });
    }

    @Test
    @Order(2)
    void appliedFluxOutputCapabilityHasHighestOutputPriority() {
        FluxEnergyOutputCapability output = new FluxEnergyOutputCapability(new FluxEnergyBuffer());
        FluxEnergyInputCapability input = new FluxEnergyInputCapability(new FluxEnergyBuffer(), "test-input");
        BuiltinEnergyProbeCapability probe = new BuiltinEnergyProbeCapability();

        assertThat(FluxEnergyOutputKind.INSTANCE.outputPriority()).isEqualTo(Integer.MAX_VALUE);
        assertThat(output.outputPriority()).isEqualTo(Integer.MAX_VALUE);
        assertThat(input.outputPriority()).isZero();

        List<cn.howxu.mmcr.api.capability.MachineCapability> sorted = RequirementHandlerSupport
                .prioritizedOutputCapabilities(List.of(probe, output));

        assertThat(sorted).first().isSameAs(output);
    }

    @Test
    @Order(3)
    void ae2HostRegistrationUsesRealFluxEntityTypesWithoutNativeFe() throws Exception {
        bindFluxEntityType(FluxEnergyInputKind.INSTANCE);
        bindFluxEntityType(FluxEnergyOutputKind.INSTANCE);
        AppliedFluxBridgeBootstrap.installForTesting(new LoadedAppliedFluxBridge());
        PortKinds.clearForTesting();
        PortKinds.register(FluxEnergyInputKind.INSTANCE);
        PortKinds.register(FluxEnergyOutputKind.INSTANCE);

        ModCapabilities.register(capabilityEvent());

        BlockState inputState = Blocks.IRON_BLOCK.defaultBlockState();
        BlockState outputState = Blocks.IRON_BLOCK.defaultBlockState();
        FluxEnergyInputInterfaceBlockEntity input = FluxEnergyInputKind.INSTANCE.entityFactory()
                .create(BlockPos.ZERO, inputState);
        FluxEnergyOutputInterfaceBlockEntity output = FluxEnergyOutputKind.INSTANCE.entityFactory()
                .create(BlockPos.ZERO, outputState);
        var inputLevel = LevelStub.create(Blocks.IRON_BLOCK, 1, 1, 1, BlockPos.ZERO);
        var outputLevel = LevelStub.create(Blocks.IRON_BLOCK, 1, 1, 1, BlockPos.ZERO);

        assertThat(input).isInstanceOf(IOPortBlockEntity.class);
        assertThat(output).isInstanceOf(IOPortBlockEntity.class);

        var inputCapabilities = input.capabilitySnapshot().capabilities();
        assertThat(inputCapabilities).hasSize(1)
                .first()
                .isInstanceOf(FluxEnergyInputCapability.class)
                .satisfies(capability -> assertThat(capability.facet(TransferFacet.class)).isEmpty());
        var outputCapabilities = output.capabilitySnapshot().capabilities();
        assertThat(outputCapabilities).hasSize(1)
                .first()
                .isInstanceOf(FluxEnergyOutputCapability.class)
                .satisfies(capability -> assertThat(capability.facet(TransferFacet.class)).isEmpty());

        BlockEntityType<?> inputType = ModBlockEntities.BES.get(INPUT_ID).get();
        BlockEntityType<?> outputType = ModBlockEntities.BES.get(OUTPUT_ID).get();
        assertThat(inputType).isNotEqualTo(ModBlockEntities.BES.get("item_input_bus").get());
        assertThat(outputType).isNotEqualTo(ModBlockEntities.BES.get("item_output_bus").get());

        assertThat(AECapabilities.IN_WORLD_GRID_NODE_HOST.getCapability(inputLevel, BlockPos.ZERO,
                inputState, input, null)).isSameAs(input);
        assertThat(AECapabilities.IN_WORLD_GRID_NODE_HOST.getCapability(outputLevel, BlockPos.ZERO,
                outputState, output, null)).isSameAs(output);

        assertThat(ModCapabilities.ENERGY_BLOCK.getCapability(inputLevel, BlockPos.ZERO,
                inputState, input, Direction.NORTH)).isNull();
        assertThat(ModCapabilities.ENERGY_BLOCK.getCapability(outputLevel, BlockPos.ZERO,
                outputState, output, Direction.NORTH)).isNull();
    }

    private static void bindFluxEntityType(IOPortKind kind) {
        Identifier id = MMCR.id(kind.id());
        MappedRegistry<BlockEntityType<?>> registry = (MappedRegistry<BlockEntityType<?>>) BuiltInRegistries.BLOCK_ENTITY_TYPE;
        registry.unfreeze(true);
        try {
            if (!registry.containsKey(id)) {
                Registry.register(registry, id,
                        new BlockEntityType<>(kind.entityFactory(), Blocks.IRON_BLOCK));
            }
        } finally {
            registry.freeze();
        }
        ModBlockEntities.BES.put(kind.id(),
                DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, id));
    }

    private static RegisterCapabilitiesEvent capabilityEvent() {
        try {
            Constructor<RegisterCapabilitiesEvent> constructor = RegisterCapabilitiesEvent.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to create capability registration event", exception);
        }
    }

    /**
     * Minimal non-zero probe capability used to verify that the AppFlux output sorts
     * ahead of an ordinary energy output capability. Lives only inside the test.
     *
     * @author howxu <dev@howxu.cn>
     */
    private static final class BuiltinEnergyProbeCapability implements cn.howxu.mmcr.api.capability.MachineCapability {
        private final cn.howxu.mmcr.api.capability.CapabilityView view =
                cn.howxu.mmcr.internal.capability.CapabilityFactories.view(
                        BuiltinCapabilityDefinitions.ENERGY_TYPE,
                        cn.howxu.mmcr.api.capability.CapabilityDirections.output(),
                        java.util.Set.of());

        @Override
        public cn.howxu.mmcr.api.capability.CapabilityType type() {
            return BuiltinCapabilityDefinitions.ENERGY_TYPE;
        }

        @Override
        public cn.howxu.mmcr.api.capability.CapabilityDirections directions() {
            return cn.howxu.mmcr.api.capability.CapabilityDirections.output();
        }

        @Override
        public cn.howxu.mmcr.api.capability.CapabilityView view() {
            return view;
        }

        @Override
        public int outputPriority() {
            return 1;
        }

        @Override
        public cn.howxu.mmcr.api.capability.plan.CapabilityOperation prepare(
                cn.howxu.mmcr.api.capability.CapabilityRequest request) {
            throw new UnsupportedOperationException("Probe capability is not backed by storage");
        }
    }
}