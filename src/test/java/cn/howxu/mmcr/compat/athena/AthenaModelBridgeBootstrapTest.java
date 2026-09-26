package cn.howxu.mmcr.compat.athena;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AthenaModelBridgeBootstrapTest {
    @Test
    void selectsUnavailableBridgeWhenAthenaIsNotLoaded() {
        assertThat(AthenaModelBridgeBootstrap.selectForTesting(false))
                .isSameAs(UnavailableAthenaModelBridge.INSTANCE);
    }
}
