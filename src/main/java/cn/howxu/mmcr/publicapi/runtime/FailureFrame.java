package cn.howxu.mmcr.publicapi.runtime;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** MMCR-provided failure trace frame. @author howxu <dev@howxu.cn> */
@ApiStatus.NonExtendable
public interface FailureFrame {
    Identifier source();
    FailurePhase phase();
    @Nullable Identifier recipeId();
    @Nullable Integer requirementIndex();
}
