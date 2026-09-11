package cn.howxu.mmcr.compat.appliedenergistics2;

import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.AE2OutputInterfaceBaseBlockEntity;
import cn.howxu.mmcr.registry.ModBlocks;
import cn.howxu.mmcr.registry.PortKinds;
import cn.howxu.mmcr.test.TestBootstrap;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the output host's direction-specific lifecycle contract.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2OutputInterfaceHostTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
        if (!ae2KeyTypesAreInitialized()) initializeAE2KeyTypes();
        bindTestAE2InterfaceItem();
    }

    @Test
    void saveChangesDoesNotNotifyInputControllers() {
        TestOutputHost host = new TestOutputHost();

        host.saveChanges();

        assertThat(host.inputNotifications).isZero();
    }

    @Test
    void removingTheHostDestroysItsManagedNode() {
        TestOutputHost host = new TestOutputHost();

        host.setRemoved();

        assertThat(host.isRemoved()).isTrue();
        assertThat(host.getMainNode().isReady()).isFalse();
    }

    private static final class TestOutputHost extends AE2OutputInterfaceBaseBlockEntity {
        private int inputNotifications;

        private TestOutputHost() {
            super(BlockPos.ZERO, ModBlocks.BLOCKS.get(PortKinds.ITEM_OUTPUT.id()).get().defaultBlockState(),
                    PortKinds.ITEM_OUTPUT);
        }

        @Override
        public CapabilitySnapshot capabilitySnapshot() {
            return new CapabilitySnapshot(List.of());
        }

        @Override
        protected void notifyControllerOfInputChange() {
            inputNotifications++;
        }
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

    private static boolean ae2KeyTypesAreInitialized() {
        try {
            return !AEKeyTypes.getAll().isEmpty();
        } catch (IllegalStateException ignored) {
            return false;
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
