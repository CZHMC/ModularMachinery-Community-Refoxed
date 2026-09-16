package cn.howxu.mmcr.compat.appliedenergistics2;

import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.ExtendedAEContributor;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.ExtendedAEContributorBootstrap;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExtendedAEBridgeTest {
    @Test
    void unavailableExtendedAeContributorAddsNoPorts() {
        ExtendedAEContributor contributor = ExtendedAEContributorBootstrap.selectForTesting(false);

        assertThat(contributor.available()).isFalse();
        assertThat(contributor.portKinds()).isEmpty();
        assertThat(contributor.isPort("eae_me_extended_input_interface")).isFalse();
    }

    @Test
    void missingLoadedExtendedAeContributorAddsNoPorts() {
        ExtendedAEContributor contributor = ExtendedAEContributorBootstrap.selectForTesting(true);

        assertThat(contributor.available()).isFalse();
        assertThat(contributor.portKinds()).isEmpty();
    }
}
