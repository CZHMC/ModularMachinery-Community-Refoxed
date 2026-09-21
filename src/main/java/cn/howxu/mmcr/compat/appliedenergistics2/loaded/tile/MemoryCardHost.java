package cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile;

import appeng.items.tools.MemoryCardItem;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Exposes the AE2 state that an MMCR port may save to and restore from a memory card.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface MemoryCardHost {
    Component memoryCardSettingsSource();

    default void exportMemoryCardSettings(DataComponentMap.Builder builder, @Nullable Player player) {
        MemoryCardItem.exportGenericSettings(this, builder);
    }

    default void importMemoryCardSettings(DataComponentMap input, @Nullable Player player) {
        MemoryCardItem.importGenericSettings(this, input, player);
    }
}
