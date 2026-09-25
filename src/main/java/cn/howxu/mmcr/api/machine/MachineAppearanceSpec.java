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
        @Nullable Identifier formedPortBaseTexture,
        Identifier controllerIdleOverlayTexture,
        Identifier controllerActiveOverlayTexture
) {
    private static final Identifier DEFAULT_IDLE_OVERLAY_TEXTURE = MMCR.id("block/overlay_basic_idle");
    private static final Identifier DEFAULT_ACTIVE_OVERLAY_TEXTURE = MMCR.id("block/overlay_basic_active");

    public MachineAppearanceSpec(Identifier machineBasicBlock, @Nullable Identifier controllerBaseTexture,
                                 @Nullable Identifier formedPortBaseTexture) {
        this(machineBasicBlock, controllerBaseTexture, formedPortBaseTexture,
                DEFAULT_IDLE_OVERLAY_TEXTURE, DEFAULT_ACTIVE_OVERLAY_TEXTURE);
    }

    public MachineAppearanceSpec {
        if (machineBasicBlock == null) throw new IllegalArgumentException("machineBasicBlock null");
        controllerIdleOverlayTexture = controllerIdleOverlayTexture == null
                ? DEFAULT_IDLE_OVERLAY_TEXTURE : controllerIdleOverlayTexture;
        controllerActiveOverlayTexture = controllerActiveOverlayTexture == null
                ? DEFAULT_ACTIVE_OVERLAY_TEXTURE : controllerActiveOverlayTexture;
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
