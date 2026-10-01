package cn.howxu.mmcr.publicapi.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/** Live text handle supplied by MMCR; consumers must not implement it.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface ControllerText {
    void append(TextScope scope, Identifier lineId, Component text);
    /** Inserts relative to a reference in the same scope; a missing or cross-scope reference is a no-op. */
    void appendAfter(TextScope scope, Identifier lineId, Identifier afterLineId, Component text);
    /** Queues replacement for the controller's post-handler flush; the current snapshot is not changed immediately. */
    void replace(Identifier lineId, Component text);
    void remove(TextScope scope, Identifier lineId);
    void clear(TextScope scope);
}
