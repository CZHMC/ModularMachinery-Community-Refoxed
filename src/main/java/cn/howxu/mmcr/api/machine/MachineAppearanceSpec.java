package cn.howxu.mmcr.api.machine;

import cn.howxu.mmcr.MMCR;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Startup-declared visual base textures for a machine family.
 *
 * @author howxu <dev@howxu.cn>
 */
public record MachineAppearanceSpec(
        Identifier machineBasicBlock,
        @Nullable Identifier controllerBaseTexture,
        @Nullable Identifier formedPortBaseTexture
) {
    public MachineAppearanceSpec {
        if (machineBasicBlock == null) throw new IllegalArgumentException("machineBasicBlock null");
    }

    public static MachineAppearanceSpec defaults() {
        return new MachineAppearanceSpec(MMCR.id("basic_casing"), null, null);
    }

    public static MachineAppearanceSpec fromBasicBlock(Identifier blockId) {
        return new MachineAppearanceSpec(blockId, null, null);
    }

    public TextureSource controllerTextureSource() {
        return new TextureSource(machineBasicBlock, controllerBaseTexture);
    }

    public TextureSource formedPortTextureSource() {
        return new TextureSource(machineBasicBlock, formedPortBaseTexture);
    }

    public record TextureSource(Identifier blockId, @Nullable Identifier overrideTexture) {
        public TextureSource {
            if (blockId == null) throw new IllegalArgumentException("blockId null");
        }
    }
}
