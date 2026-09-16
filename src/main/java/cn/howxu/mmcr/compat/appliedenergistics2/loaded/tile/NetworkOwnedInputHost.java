package cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile;

import appeng.api.stacks.AEKey;

/**
 * Host contract for AE2 interface storage whose contents originated from the network.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface NetworkOwnedInputHost {
    void recordNetworkPull(int slot, AEKey what, long amount);

    void recordNetworkReturn(int slot, AEKey what, long amount);

    long networkOwnedAmount(int slot, AEKey what);
}
