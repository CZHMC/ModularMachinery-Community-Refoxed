package cn.howxu.mmcr.publicapi.presentation;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/** Live Jade handle supplied by MMCR, a no-op when unavailable.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface JadeText {
    void append(Identifier lineId, Component text);
    void appendAfter(Identifier lineId, Identifier afterLineId, Component text);
    void replace(Identifier lineId, Component text);
    void remove(Identifier lineId);
    void clear();
}
