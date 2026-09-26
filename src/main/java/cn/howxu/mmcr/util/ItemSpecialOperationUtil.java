package cn.howxu.mmcr.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * @description: TODO
 * @author: HowXu
 * @date: 2026/9/26 15:05
 */
public class ItemSpecialOperationUtil {

    public static boolean isSpecialOperated(Player player){
        // Now shift and crouching all invoke special action
        return player.isCrouching() || Minecraft.getInstance().hasShiftDown();
    }

    public static boolean isSpecialOperated(Player player,Minecraft minecraft){
        // Now shift and crouching all invoke special action
        return player.isCrouching() || minecraft.hasShiftDown();
    }

}
