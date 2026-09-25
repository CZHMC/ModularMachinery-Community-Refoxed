package cn.howxu.mmcr.internal.block;

import cn.howxu.mmcr.LevelStub;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.machine.MachineAppearanceSpec;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.registry.ModBlockEntities;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppearanceStateResolverTest {
    private static final BlockPos POS = BlockPos.ZERO;

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void exposes_complete_unoverridden_appearance_source() {
        BlockState self = Blocks.IRON_BLOCK.defaultBlockState();
        Level level = LevelStub.createStates(java.util.Map.of(POS, self));
        var source = new MachineAppearanceSpec.TextureSource(BuiltInRegistries.BLOCK.getKey(Blocks.STONE), null);

        assertThat(AppearanceStateResolver.resolve(self, level, POS, source))
                .isEqualTo(Blocks.STONE.defaultBlockState());
    }

    @Test
    void keeps_own_state_for_explicit_texture_or_non_cube_source() {
        BlockState self = Blocks.IRON_BLOCK.defaultBlockState();
        Level level = LevelStub.createStates(java.util.Map.of(POS, self));
        var overridden = new MachineAppearanceSpec.TextureSource(BuiltInRegistries.BLOCK.getKey(Blocks.STONE),
                MMCR.id("block/custom"));
        var nonCube = new MachineAppearanceSpec.TextureSource(BuiltInRegistries.BLOCK.getKey(Blocks.TORCH), null);

        assertThat(AppearanceStateResolver.resolve(self, level, POS, overridden)).isSameAs(self);
        assertThat(AppearanceStateResolver.resolve(self, level, POS, nonCube)).isSameAs(self);
    }

    @Test
    void exposes_single_linked_component_source() {
        IOPortBlockEntity port = (IOPortBlockEntity) ModBlockEntities.BES.get("item_input_bus").get().create(
                POS, ModBlocks.BLOCKS.get("item_input_bus").get().defaultBlockState());
        var source = new MachineAppearanceSpec.TextureSource(BuiltInRegistries.BLOCK.getKey(Blocks.STONE), null);
        port.linkControllerAppearanceSource(new BlockPos(1, 0, 0), source);
        Level level = LevelStub.create(java.util.Map.of(POS, port.getBlockState().getBlock()), List.of(port));
        port.setLevel(level);

        assertThat(AppearanceStateResolver.resolveLinked(port.getBlockState(), level, POS))
                .isEqualTo(Blocks.STONE.defaultBlockState());
    }

    @Test
    void linked_io_port_reports_appearance_source_to_vanilla_rendering() {
        IOPortBlockEntity port = (IOPortBlockEntity) ModBlockEntities.BES.get("item_input_bus").get().create(
                POS, ModBlocks.BLOCKS.get("item_input_bus").get().defaultBlockState());
        port.linkControllerAppearanceSource(new BlockPos(1, 0, 0),
                new MachineAppearanceSpec.TextureSource(BuiltInRegistries.BLOCK.getKey(Blocks.STONE), null));
        Level level = LevelStub.create(java.util.Map.of(POS, port.getBlockState().getBlock()), List.of(port));
        port.setLevel(level);

        assertThat(port.getBlockState().getAppearance(level, POS, Direction.NORTH, null, null))
                .isEqualTo(Blocks.STONE.defaultBlockState());
    }
}
