package cn.howxu.mmcr.compat.athena;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * No-op Athena bridge used when Athena is absent.
 *
 * @author howxu <dev@howxu.cn>
 */
final class UnavailableAthenaModelBridge implements AthenaModelBridge {
    static final UnavailableAthenaModelBridge INSTANCE = new UnavailableAthenaModelBridge();

    private UnavailableAthenaModelBridge() {
    }

    @Override
    public boolean collectParts(BlockStateModel sourceModel, BlockAndTintGetter level, BlockPos pos,
                                BlockState appearance, RandomSource random, List<BlockStateModelPart> parts) {
        return false;
    }
}
