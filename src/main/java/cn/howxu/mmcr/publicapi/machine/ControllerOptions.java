package cn.howxu.mmcr.publicapi.machine;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Producer-created controller configuration. Consumers must not implement it. @author howxu <dev@howxu.cn> */
@ApiStatus.NonExtendable
public interface ControllerOptions {
    ControllerOptions id(Identifier id);
    ControllerOptions textures(Identifier front, Identifier otherFaces);
    ControllerOptions textures(Identifier front, Identifier side, Identifier top, Identifier bottom);
    ControllerOptions frontTexture(Identifier id);
    ControllerOptions sideTexture(Identifier id);
    ControllerOptions topTexture(Identifier id);
    ControllerOptions bottomTexture(Identifier id);
    ControllerOptions allowVerticalFacing();
    ControllerOptions allowVerticalFacing(boolean enabled);
    ControllerOptions fullyRotationallySymmetric();
    ControllerOptions fullyRotationallySymmetric(boolean enabled);
    ControllerOptions requireVerticalFacing();
    ControllerOptions requireVerticalFacing(boolean enabled);
    ControllerOptions tooltip(String... translationKeys);
    View build();

    /** Producer-created immutable declaration view. @author howxu <dev@howxu.cn> */
    @ApiStatus.NonExtendable
    interface View {
        @Nullable Identifier id();
        @Nullable Identifier frontTexture();
        @Nullable Identifier sideTexture();
        @Nullable Identifier topTexture();
        @Nullable Identifier bottomTexture();
        boolean allowVerticalFacing();
        boolean fullyRotationallySymmetric();
        boolean requireVerticalFacing();
        List<String> tooltip();
    }
}
