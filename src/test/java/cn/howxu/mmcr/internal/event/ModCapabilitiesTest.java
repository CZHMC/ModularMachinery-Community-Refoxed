package cn.howxu.mmcr.internal.event;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.LevelStub;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.capability.CapabilityRequest;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.CapabilityType;
import cn.howxu.mmcr.api.capability.CapabilityView;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.facet.CapabilityFacet;
import cn.howxu.mmcr.api.capability.facet.ResourceFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import cn.howxu.mmcr.api.capability.type.CapabilityBinding;
import cn.howxu.mmcr.api.port.PortDefinition;
import cn.howxu.mmcr.api.port.PortTierPolicy;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.storage.LongResourceStorage;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.registry.ModBlockEntities;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies native capability registration consumes binding exposure declarations.
 *
 * @author howxu <dev@howxu.cn>
 */
class ModCapabilitiesTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void selects_external_exposures_from_generic_port_bindings() {
        CapabilityBinding binding = new CapabilityBinding(
                new CapabilityType(MMCR.id("external_test")), IOType.INPUT,
                context -> null, PortTierPolicy.always(),
                new CapabilityBinding.ExternalExposure<>(MMCR.id("external_test_native"), String.class,
                        (host, ioType, side) -> "exposed"));
        PortDefinition definition = PortDefinition.of(MMCR.id("external_test_port"), binding);
        IOPortKind kind = new IOPortKind() {
            @Override
            public String id() {
                return "external_test_port";
            }

            @Override
            public IOType ioType() {
                return IOType.INPUT;
            }

            @Override
            public BlockEntityType.BlockEntitySupplier<? extends IOPortBlockEntity> entityFactory() {
                return (pos, state) -> null;
            }

            @Override
            public PortDefinition definition() {
                return definition;
            }
        };

        assertThat(ModCapabilities.externalBindings(kind)).containsExactly(binding);
    }

    @Test
    void native_resource_provider_exposes_resource_facet_without_transfer_facet() {
        ResourceOnlyCapability capability = new ResourceOnlyCapability();
        CapabilityBinding binding = new CapabilityBinding(capability.type(), CapabilityDirections.input(),
                context -> capability, PortTierPolicy.always());
        ResourceOnlyPort port = new ResourceOnlyPort(binding, capability);
        Level level = LevelStub.createWithBlockEntities(List.of(port));
        port.setLevel(level);
        int lookupsBefore = LevelStub.capabilityLookups(level);

        assertThat(ModCapabilities.resourceStorage(port, List.of(binding), Direction.NORTH, ItemResource.class))
                .isSameAs(capability.storage());

        port.enableAutoIOAndRunCycle();
        assertThat(port.autoIOCandidateCount()).isZero();
        assertThat(LevelStub.capabilityLookups(level)).isEqualTo(lookupsBefore);
    }

    private static final class ResourceOnlyPort extends IOPortBlockEntity {
        private final IOPortKind kind;
        private final CapabilitySnapshot snapshot;

        private ResourceOnlyPort(CapabilityBinding binding, MachineCapability capability) {
            super(ModBlockEntities.BES.get("item_input_bus").get(), BlockPos.ZERO,
                    ModBlocks.BLOCKS.get("item_input_bus").get().defaultBlockState());
            kind = new IOPortKind() {
                @Override public String id() { return "resource_only_test"; }
                @Override public IOType ioType() { return IOType.INPUT; }
                @Override public BlockEntityType.BlockEntitySupplier<? extends IOPortBlockEntity> entityFactory() { return null; }
                @Override public PortDefinition definition() { return PortDefinition.of(MMCR.id("resource_only_test"), binding); }
            };
            snapshot = new CapabilitySnapshot(List.of(capability));
        }

        @Override public IOType ioType() { return IOType.INPUT; }
        @Override public IOPortKind kind() { return kind; }
        @Override public CapabilitySnapshot capabilitySnapshot() { return snapshot; }

        private void enableAutoIOAndRunCycle() {
            setAutoIOEnabled(true);
            runAutoIOCycle();
        }
    }

    private static final class ResourceOnlyCapability implements MachineCapability, ResourceFacet<ItemResource> {
        private static final CapabilityType TYPE = new CapabilityType(MMCR.id("resource_only_test"));
        private final ResourceStorage<ItemResource> storage = new LongResourceStorage<>(ItemResource.class, 1, 64L,
                resource -> resource.isEmpty(), () -> {});

        @Override public CapabilityType type() { return TYPE; }
        @Override public CapabilityDirections directions() { return CapabilityDirections.input(); }
        @Override public Class<ItemResource> resourceType() { return ItemResource.class; }
        @Override public ResourceStorage<ItemResource> storage() { return storage; }
        @Override public CapabilityView view() {
            return new CapabilityView() {
                @Override public CapabilityType type() { return TYPE; }
                @Override public CapabilityDirections directions() { return CapabilityDirections.input(); }
                @Override public Set<Class<? extends CapabilityFacet>> facets() { return Set.of(ResourceFacet.class); }
            };
        }
        @Override public CapabilityOperation prepare(CapabilityRequest request) {
            return transaction -> CapabilityResult.successful();
        }
    }
}
