package cn.howxu.mmcr.api.recipe;

import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MachineOutputAggregationTest {

    @BeforeAll
    static void bootstrap() throws Exception { TestBootstrap.bootstrap(); }

    @Test
    void itemKeysCollapseDifferentCounts() {
        MachineOutput a = new MachineOutput.ItemOutput(new ItemStack(Items.STONE, 4), 1F);
        MachineOutput b = new MachineOutput.ItemOutput(new ItemStack(Items.STONE, 6), 1F);
        assertThat(MachineOutput.aggregationKey(a)).isEqualTo(MachineOutput.aggregationKey(b));
        MachineOutput merged = MachineOutput.withScaledAmount(a, 10L, 1F);
        assertThat(((MachineOutput.ItemOutput) merged).stack().getCount()).isEqualTo(10);
    }

    @Test
    void fluidKeysCollapseDifferentAmounts() {
        MachineOutput a = new MachineOutput.FluidOutput(new FluidStack(Fluids.WATER, 250), 1F);
        MachineOutput b = new MachineOutput.FluidOutput(new FluidStack(Fluids.WATER, 500), 1F);
        assertThat(MachineOutput.aggregationKey(a)).isEqualTo(MachineOutput.aggregationKey(b));
        MachineOutput merged = MachineOutput.withScaledAmount(a, 750L, 1F);
        assertThat(((MachineOutput.FluidOutput) merged).stack().getAmount()).isEqualTo(750);
    }
}
