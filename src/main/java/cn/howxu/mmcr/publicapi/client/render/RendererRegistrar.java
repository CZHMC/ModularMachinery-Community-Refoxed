package cn.howxu.mmcr.publicapi.client.render;

import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/** MMCR-provided renderer registration window; the returned map is read-only.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface RendererRegistrar {
    void register(Identifier machineId, ControllerRenderer renderer);
    Map<Identifier, ControllerRenderer> renderers();
}
