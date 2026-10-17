package cn.howxu.mmcr.compat.ae2lt;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamily;

import java.util.List;

/** Optional loading boundary; resource families are accessed only from AE2's loaded path.
 * @author howxu <dev@howxu.cn>
 */
public interface LightningBridge {
    static LightningBridge get() {
        return LightningBridgeBootstrap.bridge();
    }

    boolean available();

    void registerCapabilityDefinitions();

    List<AE2ResourceFamily<?>> resourceFamilies();
}
