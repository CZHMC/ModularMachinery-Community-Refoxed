package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKeyType;
import appeng.helpers.externalstorage.GenericStackInv;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import appeng.me.storage.NullInventory;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2ResourceFamilies;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AsyncOutputService;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.FluidResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.ItemResourceStorage;
import cn.howxu.mmcr.internal.port.FluidHatchSize;
import cn.howxu.mmcr.internal.port.ItemBusSize;
import cn.howxu.mmcr.internal.port.PortFamilyDescriptor;
import cn.howxu.mmcr.internal.port.PortFamilyIds;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the shared AE2 item and fluid resource-family registry.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2ResourceFamilyTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void registryExposesOnlyItemAndFluidFamiliesWithTheExistingPortDescriptors() {
        assertThat(AE2ResourceFamilies.all()).containsExactly(AE2ResourceFamilies.ITEM, AE2ResourceFamilies.FLUID);
        assertThat(AE2ResourceFamilies.patternFamilies())
                .extracting(PortFamilyDescriptor::familyId)
                .containsExactly(PortFamilyIds.ITEM, PortFamilyIds.FLUID, PortFamilyIds.ITEM, PortFamilyIds.FLUID);
        assertThat(AE2ResourceFamilies.inputFamilies())
                .extracting(PortFamilyDescriptor::detectionTier)
                .containsExactly(ItemBusSize.values().length, FluidHatchSize.values().length);
        assertThat(AE2ResourceFamilies.outputFamilies())
                .extracting(PortFamilyDescriptor::detectionTier)
                .containsExactly(ItemBusSize.LUDICROUS.ordinal() + 1, FluidHatchSize.VACUUM.ordinal() + 1);
        assertThat(AE2ResourceFamilies.patternFamilies())
                .extracting(PortFamilyDescriptor::ioType)
                .containsExactly(IOType.INPUT, IOType.INPUT, IOType.OUTPUT, IOType.OUTPUT);
    }

    @Test
    void itemAndFluidFamiliesConvertBothDirectionsThroughTheirSharedAdapters() {
        ItemResource item = ItemResource.of(Items.IRON_INGOT);
        FluidResource fluid = FluidResource.of(Fluids.WATER);

        assertThat(AE2ResourceFamilies.ITEM.adapter().toResource(AE2ResourceFamilies.ITEM.adapter().toKey(item)))
                .contains(item);
        assertThat(AE2ResourceFamilies.FLUID.adapter().toResource(AE2ResourceFamilies.FLUID.adapter().toKey(fluid)))
                .contains(fluid);
        assertThat(ItemResourceStorage.adapter()).isSameAs(AE2ResourceFamilies.ITEM.adapter());
        assertThat(FluidResourceStorage.adapter()).isSameAs(AE2ResourceFamilies.FLUID.adapter());
    }

    @Test
    void allInterfaceStrategiesCreateTypedViewsFromTheirFamilyEntry() {
        GenericStackInv inventory = new GenericStackInv(
                Set.of(AEKeyType.items(), AEKeyType.fluids()), null, GenericStackInv.Mode.STORAGE, 1);
        PatternProviderReturnInventory returnInventory = new PatternProviderReturnInventory(() -> {});

        assertThat(AE2ResourceFamilies.ITEM.standardInputView(inventory)).isInstanceOf(ItemResourceStorage.class);
        assertThat(AE2ResourceFamilies.FLUID.standardInputView(inventory)).isInstanceOf(FluidResourceStorage.class);
        assertThat(AE2ResourceFamilies.ITEM.stockingInputView(NullInventory.of(), List.of()).resourceType())
                .isEqualTo(ItemResource.class);
        assertThat(AE2ResourceFamilies.FLUID.stockingInputView(NullInventory.of(), List.of()).resourceType())
                .isEqualTo(FluidResource.class);
        assertThat(AE2ResourceFamilies.ITEM.outputView(inventory, NullInventory::of, IActionSource.empty(), () -> {})
                .resourceType()).isEqualTo(ItemResource.class);
        assertThat(AE2ResourceFamilies.FLUID.outputView(inventory, NullInventory::of, IActionSource.empty(), () -> {})
                .resourceType()).isEqualTo(FluidResource.class);
        assertThat(AE2ResourceFamilies.ITEM.asyncOutputView(NullInventory::of, new AsyncOutputService(),
                IActionSource.empty(), () -> {}).resourceType()).isEqualTo(ItemResource.class);
        assertThat(AE2ResourceFamilies.FLUID.asyncOutputView(NullInventory::of, new AsyncOutputService(),
                IActionSource.empty(), () -> {}).resourceType()).isEqualTo(FluidResource.class);
        assertThat(AE2ResourceFamilies.ITEM.patternInputView(NullInventory.of()).resourceType())
                .isEqualTo(ItemResource.class);
        assertThat(AE2ResourceFamilies.FLUID.patternInputView(NullInventory.of()).resourceType())
                .isEqualTo(FluidResource.class);
        assertThat(AE2ResourceFamilies.ITEM.patternOutputView(returnInventory).resourceType())
                .isEqualTo(ItemResource.class);
        assertThat(AE2ResourceFamilies.FLUID.patternOutputView(returnInventory).resourceType())
                .isEqualTo(FluidResource.class);
    }
}
