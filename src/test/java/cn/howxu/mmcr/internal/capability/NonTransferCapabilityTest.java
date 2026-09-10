package cn.howxu.mmcr.internal.capability;

import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.internal.storage.BulkItemStorage;
import cn.howxu.mmcr.internal.storage.LongFluidStorage;
import cn.howxu.mmcr.util.IOType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies capabilities can publish their non-transfer facets without exposing transfer access.
 *
 * @author howxu <dev@howxu.cn>
 */
class NonTransferCapabilityTest {
    @Test
    void itemCapabilityCanHideTransferFacet() {
        ItemBusCapability capability = new ItemBusCapability(
                null, new BulkItemStorage(64L, () -> {}), IOType.INPUT, false);

        assertThat(capability.view().facets()).doesNotContain(TransferFacet.class);
        assertThat(capability.facet(TransferFacet.class)).isEmpty();
    }

    @Test
    void fluidCapabilityCanHideTransferFacet() {
        FluidHatchCapability capability = new FluidHatchCapability(
                null, new LongFluidStorage(1, 1_000L, () -> {}), IOType.INPUT, false);

        assertThat(capability.view().facets()).doesNotContain(TransferFacet.class);
        assertThat(capability.facet(TransferFacet.class)).isEmpty();
    }
}
