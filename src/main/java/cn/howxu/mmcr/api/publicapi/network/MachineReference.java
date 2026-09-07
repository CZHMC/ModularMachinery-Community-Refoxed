package cn.howxu.mmcr.api.publicapi.network;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/** Stable public identity of a formed machine controller.
 * @author howxu <dev@howxu.cn>
 */
public record MachineReference(Identifier type, long hash) {
    public MachineReference {
        Objects.requireNonNull(type, "type");
    }

    cn.howxu.mmcr.api.network.MachineReference toInternal() {
        return new cn.howxu.mmcr.api.network.MachineReference(type, hash);
    }

    public static MachineReference fromInternal(cn.howxu.mmcr.api.network.MachineReference reference) {
        return new MachineReference(reference.type(), reference.hash());
    }
}
