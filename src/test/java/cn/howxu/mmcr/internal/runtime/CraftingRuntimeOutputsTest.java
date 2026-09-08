package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the public {@code activeOutputs()} accessor on {@link CraftingRuntime}.
 *
 * @author howxu <dev@howxu.cn>
 */
class CraftingRuntimeOutputsTest {

    @BeforeAll
    static void bootstrap() throws Exception { TestBootstrap.bootstrap(); }

    @Test
    void emptyControllerReturnsEmptyList() {
        CraftingRuntime runtime = TestBootstrap.newCraftingRuntime();
        assertThat(runtime.activeOutputs()).isEmpty();
    }

    @Test
    void activeOutputsIsDefensivelyCopied() {
        CraftingRuntime runtime = TestBootstrap.newCraftingRuntime();
        List<MachineOutput> first = runtime.activeOutputs();
        assertThatThrownBy(() -> first.add(new MachineOutput.ItemOutput(new ItemStack(Items.STONE), 1F)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
