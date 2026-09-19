package cn.howxu.mmcr.compat.appliedenergistics2;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.AsyncOutputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.InputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.OutputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.StockingInterfaceKind;
import cn.howxu.mmcr.compat.extendedae.loaded.kind.ExtendedOutputInterfaceKind;
import cn.howxu.mmcr.compat.extendedae.loaded.kind.OversizeOutputInterfaceKind;
import cn.howxu.mmcr.internal.port.IOPortKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AE2BridgeTest {
    @AfterEach
    void resetBridge() {
        AE2BridgeBootstrap.resetForTesting();
    }

    @Test
    void unavailableSelectionHasNoPorts() {
        AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(false);

        assertThat(bridge.available()).isFalse();
        assertThat(bridge.portKinds()).isEmpty();
        assertThat(bridge.isPort("ae2_me_input_interface")).isFalse();
    }

    @Test
    void testOverrideIsReturnedByGet() {
        AE2Bridge fake = AE2BridgeBootstrap.selectForTesting(false);
        AE2BridgeBootstrap.installForTesting(fake);

        assertThat(AE2Bridge.get()).isSameAs(fake);
        AE2BridgeBootstrap.resetForTesting();
    }

    @Test
    void unavailableBridgeDoesNotContributeDynamicPort() {
        AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(false);

        assertThat(bridge.portKinds()).noneMatch(kind -> kind.id().equals("ae2_me_input_interface"));
        assertThat(bridge.isPort("ae2_me_input_interface")).isFalse();
    }

    @Test
    void loadedBridgeContributesTheAe2InputKind() {
        AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(true);

        assertThat(bridge.available()).isTrue();
        assertThat(bridge.portKinds()).extracting(IOPortKind::id)
                .contains(
                        "ae2_me_input_interface",
                        "ae2_me_stocking_input_interface");
        assertThat(bridge.isPort("ae2_me_input_interface")).isTrue();
        assertThat(bridge.isPort("ae2_me_stocking_input_interface")).isTrue();
    }

    @Test
    void loadedBridgeAlsoContributesTheTwoOutputKinds() {
        AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(true);

        assertThat(bridge.portKinds()).extracting(IOPortKind::id)
                .containsExactlyInAnyOrder(
                        "ae2_me_input_interface",
                        "ae2_me_stocking_input_interface",
                        "ae2_me_output_interface",
                        "ae2_me_async_output_interface",
                        "ae2_me_pattern_interface");
    }

    @Test
    void loadedBridgeRecognisesTheTwoOutputIds() {
        AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(true);

        assertThat(bridge.isPort("ae2_me_output_interface")).isTrue();
        assertThat(bridge.isPort("ae2_me_async_output_interface")).isTrue();
        assertThat(bridge.isPort("ae2_me_pattern_interface")).isTrue();
    }

    @Test
    void output_interfaces_have_the_highest_output_priority() {
        assertThat(OutputInterfaceKind.INSTANCE.outputPriority()).isEqualTo(Integer.MAX_VALUE);
        assertThat(AsyncOutputInterfaceKind.INSTANCE.outputPriority()).isEqualTo(Integer.MAX_VALUE);
        assertThat(ExtendedOutputInterfaceKind.INSTANCE.outputPriority()).isEqualTo(Integer.MAX_VALUE);
        assertThat(OversizeOutputInterfaceKind.INSTANCE.outputPriority()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void loadedBridgeReturnsDedicatedOverlaysForEachInterfaceKind() {
        AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(true);

        assertThat(bridge.portOverlayTexture(InputInterfaceKind.INSTANCE))
                .isEqualTo(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "mmcr", "block/appliedenergistics2/ae2_input"));
        assertThat(bridge.portOverlayTexture(StockingInterfaceKind.INSTANCE))
                .isEqualTo(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "mmcr", "block/appliedenergistics2/ae2_stocking_input"));
        assertThat(bridge.portOverlayTexture(OutputInterfaceKind.INSTANCE))
                .isEqualTo(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "mmcr", "block/appliedenergistics2/ae2_output"));
        assertThat(bridge.portOverlayTexture(AsyncOutputInterfaceKind.INSTANCE))
                .isEqualTo(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "mmcr", "block/appliedenergistics2/ae2_async_output"));
        assertThat(bridge.portOverlayTexture(cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.PatternInterfaceKind.INSTANCE))
                .isEqualTo(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        "mmcr", "block/appliedenergistics2/ae2_pattern_interface"));
    }
}
