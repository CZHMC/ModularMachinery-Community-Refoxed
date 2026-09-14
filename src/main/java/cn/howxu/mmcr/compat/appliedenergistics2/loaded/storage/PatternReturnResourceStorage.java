package cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage;

import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2KeyAdapter;

/**
 * Output-only MMCR view over AE2's native pattern-provider return inventory.
 *
 * @param <R> resource type exposed by the view
 * @author howxu <dev@howxu.cn>
 */
public final class PatternReturnResourceStorage<R> extends ResourceStorage<R> {
    public PatternReturnResourceStorage(PatternProviderReturnInventory inventory, AE2KeyAdapter<R> adapter) {
        super(inventory, adapter);
    }
}
