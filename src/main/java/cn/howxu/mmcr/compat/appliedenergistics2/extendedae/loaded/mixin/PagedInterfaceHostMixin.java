package cn.howxu.mmcr.compat.appliedenergistics2.extendedae.loaded.mixin;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBaseBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import com.glodblock.github.extendedae.api.IPage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds EAE's transient two-page state only when ExtendedAE is loaded.
 *
 * @author howxu <dev@howxu.cn>
 */
@Mixin({InputInterfaceBlockEntity.class, StockingInterfaceBlockEntity.class, OutputInterfaceBaseBlockEntity.class})
public abstract class PagedInterfaceHostMixin implements IPage {
    @Unique private int page;
    @Override public void setPage(int page) { this.page = Math.clamp(page, 0, 1); }
    @Override public int getPage() { return page; }
}
