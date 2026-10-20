package cn.howxu.mmcr.api.controller;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Public handle for updating controller screen text.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface ControllerScreenText {
    void append(ControllerScreenTextScope scope, Identifier lineId, Component text);

    default void append(ControllerScreenTextScope scope, String lineId, Component text) {
        append(scope, Identifier.parse(lineId), text);
    }

    /**
     * Replaces an existing line after the current append operations have completed.
     *
     * <p>The default implementation does nothing because a custom implementation may not
     * have enough information to resolve a scope-free line ID.</p>
     */
    default void replace(Identifier lineId, Component text) {
    }

    default void replace(String lineId, Component text) {
        replace(Identifier.parse(lineId), text);
    }

    /**
     * Appends or updates a line immediately after another line in the same scope.
     * If the target line does not exist, the operation is ignored.
     *
     * <p>The default implementation appends normally for compatibility with existing custom implementations.
     * Implementations that support relative ordering should override this method.</p>
     */
    default void appendAfter(ControllerScreenTextScope scope, Identifier lineId, Identifier afterLineId, Component text) {
        append(scope, lineId, text);
    }

    default void appendAfter(ControllerScreenTextScope scope, String lineId, String afterLineId, Component text) {
        appendAfter(scope, Identifier.parse(lineId), Identifier.parse(afterLineId), text);
    }

    void remove(ControllerScreenTextScope scope, Identifier lineId);

    default void remove(ControllerScreenTextScope scope, String lineId) {
        remove(scope, Identifier.parse(lineId));
    }

    void clear(ControllerScreenTextScope scope);
}
