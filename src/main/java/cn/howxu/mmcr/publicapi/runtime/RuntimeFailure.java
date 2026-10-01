package cn.howxu.mmcr.publicapi.runtime;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;

/** MMCR-provided diagnostic status, retaining reason and full trace.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface RuntimeFailure {
    Identifier id();
    FailureSeverity severity();
    Identifier source();
    Map<String, String> details();
    @Nullable Identifier reasonId();
    @Nullable String reasonTranslationKey();
    @Nullable Integer reasonPriority();
    List<FailureFrame> trace();
}
