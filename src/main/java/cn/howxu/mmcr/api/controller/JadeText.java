package cn.howxu.mmcr.api.controller;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Optional custom text exposed by a machine controller to Jade.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface JadeText {
    void append(Identifier lineId, Component text);

    default void append(String lineId, Component text) {
        append(Identifier.parse(lineId), text);
    }

    default void appendAfter(Identifier lineId, Identifier afterLineId, Component text) {
        append(lineId, text);
    }

    default void appendAfter(String lineId, String afterLineId, Component text) {
        appendAfter(Identifier.parse(lineId), Identifier.parse(afterLineId), text);
    }

    default void replace(Identifier lineId, Component text) {
        append(lineId, text);
    }

    default void replace(String lineId, Component text) {
        replace(Identifier.parse(lineId), text);
    }

    void remove(Identifier lineId);

    default void remove(String lineId) {
        remove(Identifier.parse(lineId));
    }

    void clear();

    static JadeText noop() {
        return Noop.INSTANCE;
    }

    enum Noop implements JadeText {
        INSTANCE;

        @Override
        public void append(Identifier lineId, Component text) {
        }

        @Override
        public void remove(Identifier lineId) {
        }

        @Override
        public void clear() {
        }
    }
}
