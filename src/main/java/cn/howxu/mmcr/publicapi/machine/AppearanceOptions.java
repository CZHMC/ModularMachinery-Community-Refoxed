package cn.howxu.mmcr.publicapi.machine;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Producer-created appearance configuration. Consumers must not implement it. @author howxu <dev@howxu.cn> */
@ApiStatus.NonExtendable
public interface AppearanceOptions {
    AppearanceOptions appearance(Identifier id);
    AppearanceOptions appearance(String id);
    AppearanceOptions machineBasicBlock(Identifier id);
    AppearanceOptions machineBasicBlock(String id);
    AppearanceOptions controllerBaseTexture(Identifier id);
    AppearanceOptions formedPortBaseTexture(Identifier id);
    AppearanceOptions controllerIdleOverlayTexture(Identifier id);
    AppearanceOptions controllerActiveOverlayTexture(Identifier id);
    View build();

    /** Producer-created immutable declaration view. @author howxu <dev@howxu.cn> */
    @ApiStatus.NonExtendable
    interface View {
        @Nullable Identifier machineBasicBlock();
        @Nullable Identifier controllerBaseTexture();
        @Nullable Identifier formedPortBaseTexture();
        @Nullable Identifier controllerIdleOverlayTexture();
        @Nullable Identifier controllerActiveOverlayTexture();
    }
}
