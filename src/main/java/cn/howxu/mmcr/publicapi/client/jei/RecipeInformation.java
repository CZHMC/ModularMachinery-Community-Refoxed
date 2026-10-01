package cn.howxu.mmcr.publicapi.client.jei;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/** Localized category/recipe information, independent of JEI implementation classes.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface RecipeInformation {
    Target target();
    Identifier targetId();
    String translationKey();
    /** Immutable list; Component arguments are caller-owned deep snapshots on each read. */
    List<Object> arguments();
    /** Caller-owned component including nested translation arguments, siblings and hover text. */
    Component component();
    /** Information attachment scope.
     * @author howxu <dev@howxu.cn>
     */
    enum Target { RECIPE_POOL, RECIPE }
}
