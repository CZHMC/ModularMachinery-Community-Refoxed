package cn.howxu.mmcr.compat.jade;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineOutputAmount;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeOutputCodecTest {

    @BeforeAll
    static void bootstrap() throws Exception { TestBootstrap.bootstrap(); }

    @Test
    void roundTripsItemsAndFluids() {
        MachineOutput item = new MachineOutput.ItemOutput(new ItemStack(Items.STONE, 4), 1F);
        MachineOutput fluid = new MachineOutput.FluidOutput(new FluidStack(Fluids.WATER, 250), 0.8F);
        CompoundTag data = new CompoundTag();
        RecipeOutputCodec.write(data, List.of(new MachineOutputAmount(item, 4L),
                new MachineOutputAmount(fluid, 250L)));

        List<MachineOutputAmount> decoded = RecipeOutputCodec.read(data);

        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).output()).isInstanceOf(MachineOutput.ItemOutput.class);
        assertThat(((MachineOutput.ItemOutput) decoded.get(0).output()).stack().getCount()).isEqualTo(1);
        assertThat(decoded.get(0).amount()).isEqualTo(4L);
        assertThat(decoded.get(1).output()).isInstanceOf(MachineOutput.FluidOutput.class);
        assertThat(((MachineOutput.FluidOutput) decoded.get(1).output()).stack().getAmount()).isEqualTo(1);
        assertThat(decoded.get(1).amount()).isEqualTo(250L);
        assertThat(decoded.get(1).output().chance()).isEqualTo(0.8F);
    }

    @Test
    void emptyListWritesNothingAndReadsEmpty() {
        CompoundTag data = new CompoundTag();
        RecipeOutputCodec.write(data, List.of());
        assertThat(data.contains(RecipeOutputCodec.OUTPUT_KEY)).isFalse();
        assertThat(RecipeOutputCodec.read(data)).isEmpty();
    }

    @Test
    void preservesLongFluidAmountsWithAStackTemplate() {
        CompoundTag data = new CompoundTag();
        RecipeOutputCodec.write(data, List.of(new MachineOutputAmount(
                new MachineOutput.FluidOutput(new FluidStack(Fluids.WATER, 1), 1F), Long.MAX_VALUE)));

        List<MachineOutputAmount> decoded = RecipeOutputCodec.read(data);

        assertThat(decoded).singleElement().satisfies(output -> {
            assertThat(output.amount()).isEqualTo(Long.MAX_VALUE);
            assertThat(((MachineOutput.FluidOutput) output.output()).stack().getAmount()).isEqualTo(1);
        });
    }

    @Test
    void missingKeyReadsAsEmpty() {
        assertThat(RecipeOutputCodec.read(new CompoundTag())).isEmpty();
    }
}
