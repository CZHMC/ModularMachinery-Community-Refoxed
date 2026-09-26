package cn.howxu.mmcr.util;

import net.minecraft.world.entity.player.Player;

/**
 * @description: TODO
 * @author: HowXu
 * @date: 2026/9/26 15:05
 */
public class ItemSpecialOperationUtil {

    public static boolean isSpecialOperated(Player player) {
        return player.isCrouching() || player.isShiftKeyDown();
    }
}
