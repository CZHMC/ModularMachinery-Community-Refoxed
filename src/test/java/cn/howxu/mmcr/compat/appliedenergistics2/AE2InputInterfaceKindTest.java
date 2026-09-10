package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import com.mojang.serialization.Lifecycle;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.facet.TransferFacet;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2InputInterfaceKind;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.internal.port.PortFamilyDescriptor;
import cn.howxu.mmcr.internal.port.PortFamilyIds;
import cn.howxu.mmcr.registry.ModBlockEntities;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
    void entityViewsShareStorageIdentityAndDoNotExposeTransferFacet() {
        AE2InputInterfaceBlockEntity entity = AE2InputInterfaceKind.INSTANCE.entityFactory()
                .create(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());

        assertThat(entity.itemStorage().reservationIdentity())
                .isSameAs(entity.fluidStorage().reservationIdentity());
        assertThat(entity.capabilitySnapshot().capabilities())
                .allSatisfy(capability -> assertThat(capability.facet(TransferFacet.class)).isEmpty());
    }

    private static boolean ae2KeyTypesAreInitialized() {
        try {
            return !AEKeyTypes.getAll().isEmpty();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static void bindTestEntityType() {
        Identifier id = MMCR.id(AE2InputInterfaceKind.INSTANCE.id());
        MappedRegistry<BlockEntityType<?>> registry = (MappedRegistry<BlockEntityType<?>>) BuiltInRegistries.BLOCK_ENTITY_TYPE;
        registry.unfreeze(true);
        try {
            if (!registry.containsKey(id)) {
                Registry.register(registry, id,
                        new BlockEntityType<>(AE2InputInterfaceKind.INSTANCE.entityFactory(), Blocks.IRON_BLOCK));
            }
        } finally {
            registry.freeze();
        }
        ModBlockEntities.BES.put(AE2InputInterfaceKind.INSTANCE.id(),
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
