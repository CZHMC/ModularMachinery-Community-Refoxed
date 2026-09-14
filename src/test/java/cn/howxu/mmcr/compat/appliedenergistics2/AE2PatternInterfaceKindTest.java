package cn.howxu.mmcr.compat.appliedenergistics2;

import cn.howxu.mmcr.compat.appliedenergistics2.loaded.LoadedAE2Bridge;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.PatternInterfaceKind;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.internal.port.FluidHatchSize;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.port.ItemBusSize;
import cn.howxu.mmcr.internal.port.PortFamilyDescriptor;
import cn.howxu.mmcr.internal.port.PortFamilyIds;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the public registration contract of the MMCR AE2 pattern interface.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2PatternInterfaceKindTest {
    @Test
    void loadedBridgeRegistersThePatternInterfaceKind() {
        LoadedAE2Bridge bridge = new LoadedAE2Bridge();
        IOPortKind kind = bridge.portKinds().stream()
                .filter(candidate -> candidate.id().equals("ae2_me_pattern_interface"))
                .findFirst()
                .orElseThrow();

        assertThat(bridge.portKinds()).hasSize(5);
        assertThat(kind.modDependencies()).containsExactly("ae2");
        assertThat(kind).isSameAs(PatternInterfaceKind.INSTANCE);
        assertThat(kind.families()).containsExactly(
                new PortFamilyDescriptor(PortFamilyIds.ITEM, IOType.INPUT, ItemBusSize.values().length,
                        List.of("item_input_bus")),
                new PortFamilyDescriptor(PortFamilyIds.FLUID, IOType.INPUT, FluidHatchSize.values().length,
                        List.of("fluid_input_hatch")),
                new PortFamilyDescriptor(PortFamilyIds.ITEM, IOType.OUTPUT, ItemBusSize.LUDICROUS.ordinal() + 1,
                        List.of("item_output_bus")),
                new PortFamilyDescriptor(PortFamilyIds.FLUID, IOType.OUTPUT, FluidHatchSize.VACUUM.ordinal() + 1,
                        List.of("fluid_output_hatch")));
        assertThat(kind.definition().bindings())
                .extracting(binding -> binding.type().id())
                .containsExactly(PortFamilyIds.ITEM, PortFamilyIds.FLUID, PortFamilyIds.ITEM, PortFamilyIds.FLUID);
        assertThat(kind.definition().bindings())
                .allSatisfy(binding -> assertThat(binding.directions()).isEqualTo(CapabilityDirections.bidirectional()));
        assertThat(bridge.portOverlayTexture(kind)).isEqualTo(Identifier.fromNamespaceAndPath(
                "mmcr", "block/appliedenergistics2/ae2_pattern_interface"));
    }
}
