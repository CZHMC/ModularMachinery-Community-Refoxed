package cn.howxu.mmcr.registry;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandlerSupport;
import cn.howxu.mmcr.compat.appliedflux.AppliedFluxBridge;
import cn.howxu.mmcr.compat.appliedflux.AppliedFluxBridgeBootstrap;

import cn.howxu.mmcr.compat.appliedflux.loaded.capability.FluxEnergyInputCapability;
import cn.howxu.mmcr.compat.appliedflux.loaded.capability.FluxEnergyOutputCapability;
import cn.howxu.mmcr.compat.appliedflux.loaded.kind.FluxEnergyInputKind;
import cn.howxu.mmcr.compat.appliedflux.loaded.kind.FluxEnergyOutputKind;
import cn.howxu.mmcr.compat.appliedflux.loaded.storage.FluxEnergyBuffer;
import cn.howxu.mmcr.internal.capability.BuiltinCapabilityDefinitions;
import cn.howxu.mmcr.internal.port.EnergyHatchSize;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.test.TestBootstrap;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies AppFlux port registration, optional capability boundary and resource presence.
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
    void unavailableAppliedFluxDoesNotAddKinds() {
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
    @Order(4)
    void fluxCapabilitiesDoNotExposeTransferFacet() {
        FluxEnergyInputCapability input = new FluxEnergyInputCapability(new FluxEnergyBuffer(), "test-input");
        FluxEnergyOutputCapability output = new FluxEnergyOutputCapability(new FluxEnergyBuffer());

        assertThat(input.facet(TransferFacet.class)).isEmpty();
        assertThat(output.facet(TransferFacet.class)).isEmpty();
    }

    @Test
    @Order(5)
    void appFluxOverlayTexturesAndI18nKeysAreBundled() {
        assertBundleTexture("block/appliedflux/appflux_input.png");
        assertBundleTexture("block/appliedflux/appflux_output.png");

        for (String key : List.of(
                "config.jade.plugin_mmcr.appflux_me_flux_input_interface",
                "config.jade.plugin_mmcr.appflux_me_flux_output_interface",
                "jade.mmcr.ae2_input_interface_grid.connected",
                "jade.mmcr.ae2_input_interface_grid.disconnected",
                "jade.mmcr.ae2_input_interface_grid.energy",
                "gui.mmcr.failure.appflux_unavailable",
                "gui.mmcr.failure.appflux_input_missing",
                "gui.mmcr.failure.appflux_output_blocked",
                "gui.mmcr.failure.appflux_unsupported_request")) {
            assertLangKeyPresent(key);
        }
    }

    private static void assertBundleTexture(String relativePath) {
        Path path = projectResource("src/main/resources/assets/mmcr/textures/" + relativePath);
        assertThat(Files.isRegularFile(path))
                .as("texture %s must be present at %s", relativePath, path)
                .isTrue();
        try {
            long size = Files.size(path);
            assertThat(size)
                    .as("texture %s must not be empty", relativePath)
                    .isPositive();
        } catch (IOException exception) {
            throw new AssertionError("Unable to read texture " + relativePath, exception);
        }
    }

    private static void assertLangKeyPresent(String key) {
        for (String suffix : List.of("en_us.json", "zh_cn.json")) {
            Path path = projectResource("src/main/resources/assets/mmcr/lang/" + suffix);
            assertThat(Files.isRegularFile(path))
                    .as("language file %s must be present at %s", suffix, path)
                    .isTrue();
            try {
                String content = Files.readString(path);
                assertThat(content.contains(String.format(Locale.ROOT, "\"%s\"", key)))
                        .as("language file %s must contain key %s", suffix, key)
                        .isTrue();
            } catch (IOException exception) {
                throw new AssertionError("Unable to read language file " + suffix, exception);
            }
        }
    }

    private static Path projectResource(String relative) {
        Path current = Path.of(".").toAbsolutePath();
        for (int depth = 0; depth < 6; depth++) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
            if (current == null) break;
        }
        return Path.of(relative).toAbsolutePath();
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
