package cn.howxu.mmcr.api.capability.type;

import cn.howxu.mmcr.internal.capability.BuiltinCapabilityDefinitions;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.port.PortTierPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies capability binding exposure boundaries.
 *
 * @author howxu <dev@howxu.cn>
 */
class CapabilityBindingTest {
    @Test
    void internalOnlyBindingDoesNotExposeNativeTransfer() {
        CapabilityBinding binding = CapabilityBinding.internalOnly(
                BuiltinCapabilityDefinitions.ENERGY_TYPE,
                CapabilityDirections.input(),
                context -> null,
                PortTierPolicy.always());

        assertThat(binding.nativeTransferExposure()).isFalse();
        assertThat(binding.externalExposure()).isEmpty();
    }

    @Test
    void existing_binding_constructors_keep_native_transfer_exposure() {
        CapabilityBinding binding = new CapabilityBinding(
                BuiltinCapabilityDefinitions.ENERGY_TYPE,
                CapabilityDirections.input(),
                context -> null,
                PortTierPolicy.always());

        assertThat(binding.nativeTransferExposure()).isTrue();
    }
}
