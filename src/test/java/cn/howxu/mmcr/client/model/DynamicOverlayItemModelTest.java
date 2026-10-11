package cn.howxu.mmcr.client.model;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.MachineAppearanceSpec;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.test.TestBootstrap;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.render.DynamicAtlasAllocator;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicOverlayItemModelTest {
    @AfterEach
    void clearAppearanceCache() {
        MachineAppearanceCache.replaceSnapshot(Map.of());
    }

    @Test
    void controller_items_display_the_idle_state_overlay() {
        var machineId = MMCR.id("stateful_controller");
        MachineAppearanceCache.replaceSnapshot(Map.of(machineId, new MachineAppearanceSpec(
                MMCR.id("basic_casing"), null, null,
                MMCR.id("block/custom_idle"), MMCR.id("block/custom_active"))));

        var description = DynamicOverlayItemModel.Description.controller(machineId);

        assertThat(description.stateOverlayTexture()).isEqualTo(MMCR.id("block/custom_idle"));
    }

    @ParameterizedTest
    @CsvSource({"false, false, false", "true, false, false", "false, true, false", "false, false, true"})
    void gui_cache_refreshes_when_any_item_layer_is_animated(boolean animatedBase, boolean animatedOverlay,
                                                           boolean animatedStateOverlay) throws Exception {
        TestBootstrap.bootstrap();
        var machineId = MMCR.id("test_cube");
        var baseTexture = MMCR.id("block/test_item_base");
        var defaults = MachineAppearanceSpec.defaults();
        MachineAppearanceCache.replaceSnapshot(Map.of(machineId, new MachineAppearanceSpec(
                defaults.machineBasicBlock(), baseTexture, null)));
        var description = DynamicOverlayItemModel.Description.controller(machineId);

        try (var staticSprite = sprite(false); var animatedSprite = sprite(true)) {
            var model = new DynamicOverlayItemModel(Map.of(description.baseModel(),
                    new ModelRenderProperties(true, new Material.Baked(staticSprite, false), ItemTransforms.NO_TRANSFORMS)),
                    texture -> {
                        boolean animated = texture.equals(baseTexture) ? animatedBase
                                : texture.equals(description.stateOverlayTexture()) ? animatedStateOverlay
                                : animatedOverlay;
                        return new Material.Baked(animated ? animatedSprite : staticSprite, false);
                    }, new Matrix4f());
            var renderState = new ItemStackRenderState();
            model.update(renderState, new ItemStack(ModBlocks.controllerFor(machineId).get().asItem()),
                    null, ItemDisplayContext.GUI, null, null, 0);

            boolean animated = animatedBase || animatedOverlay || animatedStateOverlay;
            assertThat(renderState.isAnimated()).isEqualTo(animated);
            var allocator = new DynamicAtlasAllocator<Object>(1, 1);
            allocator.getOrAllocate(description, renderState.isAnimated());
            allocator.endFrame();
            assertThat(allocator.getOrAllocate(description, renderState.isAnimated()).state())
                    .isEqualTo(animated ? DynamicAtlasAllocator.SlotState.STALE : DynamicAtlasAllocator.SlotState.READY);
        }
    }

    @Test
    void runtime_item_quads_follow_the_reloaded_atlas() throws Exception {
        TestBootstrap.bootstrap();
        var machineId = MMCR.id("test_cube");
        var base = MMCR.id("block/test_runtime_item_base");
        MachineAppearanceCache.replaceSnapshot(Map.of(machineId,
                new MachineAppearanceSpec(MMCR.id("basic_casing"), base, null)));
        var description = DynamicOverlayItemModel.Description.controller(machineId);
        Map<Identifier, TextureAtlasSprite> sprites = new HashMap<>();
        try (var particle = sprite(false)) {
            var model = new DynamicOverlayItemModel(Map.of(description.baseModel(),
                    new ModelRenderProperties(true, new Material.Baked(particle, false), ItemTransforms.NO_TRANSFORMS)),
                    texture -> new Material.Baked(sprites.computeIfAbsent(texture, id -> sprite(false)), false), new Matrix4f());
            var stack = new ItemStack(ModBlocks.controllerFor(machineId).get().asItem());
            var renderState = new ItemStackRenderState();
            model.update(renderState, stack, null, ItemDisplayContext.GUI, null, null, 0);
            var originalSprites = List.copyOf(sprites.values());
            assertThat(renderState.isAnimated()).isFalse();

            sprites.replaceAll((texture, oldSprite) -> {
                oldSprite.close();
                return sprite(true);
            });
            renderState.clear();
            model.update(renderState, stack, null, ItemDisplayContext.GUI, null, null, 0);

            var layersField = ItemStackRenderState.class.getDeclaredField("layers");
            layersField.setAccessible(true);
            var layers = (ItemStackRenderState.LayerRenderState[]) layersField.get(renderState);
            assertThat(layers[0].prepareQuadList()).hasSize(6).allSatisfy(quad ->
                    assertThat(quad.materialInfo().sprite()).isSameAs(sprites.get(base)));
            assertThat(layers[1].prepareQuadList()).extracting(quad -> quad.materialInfo().sprite())
                    .containsExactly(sprites.get(description.overlayTextures().getFirst()),
                            sprites.get(description.stateOverlayTexture()))
                    .doesNotContainAnyElementsOf(originalSprites);
            assertThat(renderState.isAnimated()).isTrue();
        } finally {
            sprites.values().forEach(TextureAtlasSprite::close);
        }
    }

    private static TextureAtlasSprite sprite(boolean animated) {
        NativeImage image = new NativeImage(1, animated ? 2 : 1, true);
        var animation = new AnimationMetadataSection(Optional.empty(), Optional.empty(), Optional.empty(), 1, false);
        var contents = new SpriteContents(MMCR.id(animated ? "block/test_animated" : "block/test_static"),
                new FrameSize(1, 1), image, animated ? Optional.of(animation) : Optional.empty(), List.of(), Optional.empty());
        return new TextureAtlasSprite(TextureAtlas.LOCATION_BLOCKS, contents, 1, 1, 0, 0, 0) {};
    }
}
