package cn.howxu.mmcr.api.publicapi.machine;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Machine appearance declaration.
 *
 * @author howxu <dev@howxu.cn>
 */
public record AppearanceSpec(
        Identifier machineBasicBlock,
        Identifier controllerBaseTexture,
        Identifier formedPortBaseTexture,
        Identifier controllerIdleOverlayTexture,
        Identifier controllerActiveOverlayTexture) {

    public AppearanceSpec(Identifier machineBasicBlock, Identifier controllerBaseTexture, Identifier formedPortBaseTexture) {
        this(machineBasicBlock, controllerBaseTexture, formedPortBaseTexture, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for machine appearance declarations.
     *
     * @author howxu <dev@howxu.cn>
     */
    public static final class Builder {
        private Identifier machineBasicBlock;
        private Identifier controllerBaseTexture;
        private Identifier formedPortBaseTexture;
        private Identifier controllerIdleOverlayTexture;
        private Identifier controllerActiveOverlayTexture;

        public Builder appearance(Identifier machineBasicBlock) {
            return machineBasicBlock(machineBasicBlock);
        }

        public Builder appearance(String machineBasicBlock) {
            return appearance(Identifier.parse(machineBasicBlock));
        }

        public Builder machineBasicBlock(Identifier machineBasicBlock) {
            this.machineBasicBlock = Objects.requireNonNull(machineBasicBlock, "machineBasicBlock");
            return this;
        }

        public Builder machineBasicBlock(String machineBasicBlock) {
            return machineBasicBlock(Identifier.parse(machineBasicBlock));
        }

        public Builder controllerBaseTexture(Identifier controllerBaseTexture) {
            this.controllerBaseTexture = Objects.requireNonNull(controllerBaseTexture, "controllerBaseTexture");
            return this;
        }

        public Builder formedPortBaseTexture(Identifier formedPortBaseTexture) {
            this.formedPortBaseTexture = Objects.requireNonNull(formedPortBaseTexture, "formedPortBaseTexture");
            return this;
        }

        public Builder controllerIdleOverlayTexture(Identifier controllerIdleOverlayTexture) {
            this.controllerIdleOverlayTexture = Objects.requireNonNull(controllerIdleOverlayTexture, "controllerIdleOverlayTexture");
            return this;
        }

        public Builder controllerActiveOverlayTexture(Identifier controllerActiveOverlayTexture) {
            this.controllerActiveOverlayTexture = Objects.requireNonNull(controllerActiveOverlayTexture, "controllerActiveOverlayTexture");
            return this;
        }

        public AppearanceSpec build() {
            return new AppearanceSpec(machineBasicBlock, controllerBaseTexture, formedPortBaseTexture,
                    controllerIdleOverlayTexture, controllerActiveOverlayTexture);
        }
    }
}
