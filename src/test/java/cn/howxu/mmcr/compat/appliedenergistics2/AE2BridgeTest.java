package cn.howxu.mmcr.compat.appliedenergistics2;

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
                        "ae2_me_async_output_interface");
    }

    @Test
    void loadedBridgeRecognisesTheTwoOutputIds() {
        AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(true);

        assertThat(bridge.isPort("ae2_me_output_interface")).isTrue();
        assertThat(bridge.isPort("ae2_me_async_output_interface")).isTrue();
    }

    @Test
    void loadedBridgeReturnsOverlayForAllFourInterfaceIds() {
        AE2Bridge bridge = AE2BridgeBootstrap.selectForTesting(true);
        var expected = net.minecraft.resources.Identifier.fromNamespaceAndPath("ae2", "block/interface");

        assertThat(bridge.portKinds())
                .allSatisfy(kind -> assertThat(bridge.portOverlayTexture(kind)).isEqualTo(expected));
    }
}
