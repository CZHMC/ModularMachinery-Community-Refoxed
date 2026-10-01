package cn.howxu.mmcr.publicapi.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Java-only custom controller renderer; vanilla render submission types are client-only.
 * @author howxu <dev@howxu.cn>
 */
@FunctionalInterface
public interface ControllerRenderer {
    void render(ControllerRenderContext context, PoseStack poses, SubmitNodeCollector nodes, CameraRenderState camera);
    default boolean shouldRenderOffScreen() { return false; }
    default int getViewDistance() { return 64; }
}
