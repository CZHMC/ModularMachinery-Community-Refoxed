package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.GenericStack;
import appeng.api.AECapabilities;
import com.mojang.serialization.Lifecycle;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.LevelStub;
import cn.howxu.mmcr.api.capability.facet.OperationFacet;
import cn.howxu.mmcr.api.capability.facet.ResourceFacet;
import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.compat.appliedenergistics2.AE2BridgeBootstrap;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2InputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2StockingInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2StockingInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.LoadedAE2Bridge;
import cn.howxu.mmcr.internal.event.ModCapabilities;
import cn.howxu.mmcr.internal.tile.IOPortBlockEntity;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.port.FluidHatchSize;
import cn.howxu.mmcr.internal.port.ItemBusSize;
import cn.howxu.mmcr.internal.port.PortFamilyDescriptor;
import cn.howxu.mmcr.internal.port.PortFamilyIds;
import cn.howxu.mmcr.registry.ModBlockEntities;
import cn.howxu.mmcr.registry.PortKinds;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the AE2 input interface kind and its shared storage host.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2InputInterfaceKindTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
        if (!ae2KeyTypesAreInitialized()) initializeAE2KeyTypes();
        bindTestAE2InterfaceItem();
        bindTestEntityType();
        AE2BridgeBootstrap.installForTesting(new LoadedAE2Bridge());
        PortKinds.clearForTesting();
        PortKinds.register(AE2StockingInterfaceKind.INSTANCE);
    }

    @AfterAll
    static void cleanup() {
        PortKinds.clearForTesting();
        AE2BridgeBootstrap.resetForTesting();
    }

    @Test
    void ae2KindCountsOneItemAndOneFluidInput() {
        IOPortKind kind = AE2InputInterfaceKind.INSTANCE;

        assertThat(kind.id()).isEqualTo("ae2_me_input_interface");
        assertThat(kind.ioType()).isEqualTo(IOType.INPUT);
        assertThat(kind.families()).extracting(PortFamilyDescriptor::familyId)
                .containsExactlyInAnyOrder(PortFamilyIds.ITEM, PortFamilyIds.FLUID);
        assertThat(kind.definition().bindings()).extracting(binding -> binding.type().id())
                .containsExactlyInAnyOrder(PortFamilyIds.ITEM, PortFamilyIds.FLUID);
    }

    @Test
    void stockingKindCountsOneItemAndOneFluidInput() {
        IOPortKind kind = AE2StockingInterfaceKind.INSTANCE;

        assertThat(kind.id()).isEqualTo("ae2_me_stocking_input_interface");
        assertThat(kind.ioType()).isEqualTo(IOType.INPUT);
        assertThat(kind.families()).extracting(PortFamilyDescriptor::familyId)
                .containsExactlyInAnyOrder(PortFamilyIds.ITEM, PortFamilyIds.FLUID);
        assertThat(kind.definition().bindings()).extracting(binding -> binding.type().id())
                .containsExactlyInAnyOrder(PortFamilyIds.ITEM, PortFamilyIds.FLUID);
    }

    @Test
    void ae2InputKindsOutrankEveryOrdinaryInputPort() {
        assertThat(AE2InputInterfaceKind.INSTANCE.families())
                .extracting(PortFamilyDescriptor::detectionTier)
                .containsExactlyInAnyOrder(ItemBusSize.values().length, FluidHatchSize.values().length);
        assertThat(AE2StockingInterfaceKind.INSTANCE.families())
                .extracting(PortFamilyDescriptor::detectionTier)
                .containsExactlyInAnyOrder(ItemBusSize.values().length, FluidHatchSize.values().length);
    }

    @Test
    void entityViewsShareStorageIdentityAndDoNotExposeTransferFacet() {
        AE2InputInterfaceBlockEntity entity = ordinaryEntity();

        assertThat(entity.kind()).isSameAs(AE2InputInterfaceKind.INSTANCE);
        assertThat(entity.ioType()).isEqualTo(IOType.INPUT);
        assertThat(entity.itemStorage().reservationIdentity())
                .isSameAs(entity.fluidStorage().reservationIdentity());
        var capabilities = entity.capabilitySnapshot().capabilities();
        assertThat(capabilities).hasSize(2)
                .extracting(capability -> capability.type().id())
                .containsExactlyInAnyOrder(PortFamilyIds.ITEM, PortFamilyIds.FLUID);
        assertThat(capabilities).allSatisfy(capability -> {
            assertThat(capability.directions().supports(IOType.INPUT)).isTrue();
            assertThat(capability.directions().supports(IOType.OUTPUT)).isFalse();
            assertThat(capability.view().type()).isEqualTo(capability.type());
            assertThat(capability.view().directions().supports(IOType.INPUT)).isTrue();
            assertThat(capability.view().directions().supports(IOType.OUTPUT)).isFalse();
            assertThat(capability.facet(TransferFacet.class)).isEmpty();
        });
    }

    @Test
    void entityFactoryCreatesOrdinaryInterfaceHost() {
        var entity = AE2InputInterfaceKind.INSTANCE.entityFactory()
                .create(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());

        assertThat(entity).isExactlyInstanceOf(AE2InputInterfaceBlockEntity.class);
    }

    @Test
    void entityFactoryCreatesStockingInterfaceHost() {
        var entity = AE2StockingInterfaceKind.INSTANCE.entityFactory()
                .create(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());

        assertThat(entity).isExactlyInstanceOf(AE2StockingInterfaceBlockEntity.class);
    }

    @Test
    void stockingEntityCapabilitiesUseNetworkStorageWithoutTransferFacet() {
        AE2StockingInterfaceBlockEntity entity = newStockingEntity();

        var capabilities = entity.capabilitySnapshot().capabilities();
        assertThat(capabilities).hasSize(2)
                .extracting(capability -> capability.type().id())
                .containsExactlyInAnyOrder(PortFamilyIds.ITEM, PortFamilyIds.FLUID);
        assertThat(capabilities).allSatisfy(capability -> {
            assertThat(capability.directions().supports(IOType.INPUT)).isTrue();
            assertThat(capability.directions().supports(IOType.OUTPUT)).isFalse();
            assertThat(capability.facet(ResourceFacet.class)).isPresent();
            assertThat(capability.facet(OperationFacet.class)).isPresent();
            assertThat(capability.facet(TransferFacet.class)).isEmpty();
        });
    }

    @Test
    void stockingCapabilityIsNotProjectedToNativeHandlers() {
        AE2StockingInterfaceBlockEntity entity = newStockingEntity();
        RegisterCapabilitiesEvent event = capabilityEvent();
        ModCapabilities.register(event);

        var state = Blocks.IRON_BLOCK.defaultBlockState();
        var level = LevelStub.create(Blocks.IRON_BLOCK, 1, 1, 1, BlockPos.ZERO);
        assertThat(ModCapabilities.ITEM_BLOCK.getCapability(level, BlockPos.ZERO, state, entity, Direction.NORTH))
                .isNull();
        assertThat(ModCapabilities.FLUID_BLOCK.getCapability(level, BlockPos.ZERO, state, entity, Direction.NORTH))
                .isNull();
        assertThat(AECapabilities.GENERIC_INTERNAL_INV.getCapability(level, BlockPos.ZERO, state, entity,
                Direction.NORTH)).isNull();
        assertThat(AECapabilities.ME_STORAGE.getCapability(level, BlockPos.ZERO, state, entity,
                Direction.NORTH)).isNull();
        assertThat(AECapabilities.IN_WORLD_GRID_NODE_HOST.getCapability(level, BlockPos.ZERO, state, entity,
                null)).isSameAs(entity);
    }

    @Test
    void configMarkersAlwaysUseOneResource() {
        AE2StockingInterfaceBlockEntity entity = newStockingEntity();

        entity.getInterfaceLogic().getConfig().setStack(0,
                new GenericStack(AEItemKey.of(Items.IRON_INGOT), 64L));

        assertThat(entity.getInterfaceLogic().getConfig().getAmount(0)).isEqualTo(1L);
    }

    @Test
    void fluidMarkersDefaultToOneBucket() {
        AE2StockingInterfaceBlockEntity entity = newStockingEntity();

        entity.getInterfaceLogic().getConfig().setStack(1,
                new GenericStack(AEFluidKey.of(Fluids.WATER), 1L));

        assertThat(entity.getInterfaceLogic().getConfig().getAmount(1)).isEqualTo(1_000L);
    }

    @Test
    void normalizedConfigMarkerCanStillBeCleared() {
        AE2StockingInterfaceBlockEntity entity = newStockingEntity();
        var config = entity.getInterfaceLogic().getConfig();

        config.setStack(0, new GenericStack(AEItemKey.of(Items.IRON_INGOT), 64L));
        assertThat(config.getAmount(0)).isEqualTo(1L);

        config.setStack(0, null);

        assertThat(config.getStack(0)).isNull();
    }

    @Test
    void stockingSaveWithoutGridClearsStorageMirror() {
        AE2StockingInterfaceBlockEntity entity = newStockingEntity();
        entity.getInterfaceLogic().getConfig().setStack(0,
                new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1L));
        entity.getInterfaceLogic().getStorage().setStack(0,
                new GenericStack(AEItemKey.of(Items.IRON_INGOT), 8L));

        entity.saveChanges();

        assertThat(entity.getInterfaceLogic().getStorage().getStack(0)).isNull();
        assertThat(entity.itemStorage().amount(0)).isZero();
    }

    private static AE2InputInterfaceBlockEntity ordinaryEntity() {
        return AE2InputInterfaceKind.INSTANCE.entityFactory()
                .create(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());
    }

    private static AE2StockingInterfaceBlockEntity newStockingEntity() {
        try {
            Constructor<AE2StockingInterfaceBlockEntity> constructor =
                    AE2StockingInterfaceBlockEntity.class.getDeclaredConstructor(
                            BlockPos.class, BlockState.class,
                            IOPortKind.class);
            constructor.setAccessible(true);
            return constructor.newInstance(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState(),
                    AE2StockingInterfaceKind.INSTANCE);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to construct stocking interface test host", exception);
        }
    }

    private static RegisterCapabilitiesEvent capabilityEvent() {
        try {
            Constructor<RegisterCapabilitiesEvent> constructor = RegisterCapabilitiesEvent.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to create capability registration event", exception);
        }
    }

    private static boolean ae2KeyTypesAreInitialized() {
        try {
            return !AEKeyTypes.getAll().isEmpty();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static void bindTestEntityType() {
        bindTestEntityType(AE2InputInterfaceKind.INSTANCE);
        bindTestEntityType(AE2StockingInterfaceKind.INSTANCE);
    }

    private static void bindTestEntityType(IOPortKind kind) {
        Identifier id = MMCR.id(kind.id());
        MappedRegistry<BlockEntityType<?>> registry = (MappedRegistry<BlockEntityType<?>>) BuiltInRegistries.BLOCK_ENTITY_TYPE;
        registry.unfreeze(true);
        try {
            if (!registry.containsKey(id)) {
                Registry.register(registry, id,
                        new BlockEntityType<>(kind.entityFactory(), Blocks.IRON_BLOCK));
            }
        } finally {
            registry.freeze();
        }
        ModBlockEntities.BES.put(kind.id(),
                DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, id));
    }

    private static void bindTestAE2InterfaceItem() {
        Identifier id = Identifier.fromNamespaceAndPath("ae2", "interface");
        MappedRegistry<Item> registry = (MappedRegistry<Item>) BuiltInRegistries.ITEM;
        registry.unfreeze(true);
        try {
            if (!registry.containsKey(id)) {
                Registry.register(registry, id, new Item(new Item.Properties().setId(
                        ResourceKey.create(Registries.ITEM, id))));
            }
        } finally {
            registry.freeze();
        }
    }

    private static void initializeAE2KeyTypes() {
        MappedRegistry<AEKeyType> registry = new MappedRegistry<>(AEKeyType.REGISTRY_KEY, Lifecycle.stable());
        AEKeyTypesInternal.setRegistry(registry);
        Registry.register(registry, AEKeyType.items().getId(), AEKeyType.items());
        Registry.register(registry, AEKeyType.fluids().getId(), AEKeyType.fluids());
        registry.freeze();
    }
}
