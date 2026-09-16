package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import com.mojang.serialization.Lifecycle;
import cn.howxu.mmcr.LevelStub;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.ExtendedAEContributor;
import cn.howxu.mmcr.compat.appliedenergistics2.extendedae.ExtendedAEContributorBootstrap;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.LoadedAE2Bridge;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.InputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.AsyncOutputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.OutputInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.PatternInterfaceKind;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.kind.StockingInterfaceKind;
import cn.howxu.mmcr.internal.port.IOPortKind;
import cn.howxu.mmcr.registry.ModBlockEntities;
import cn.howxu.mmcr.test.TestBootstrap;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import snownee.jade.api.IWailaCommonRegistration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the optional ExtendedAE contribution boundary in the AE2 bridge.
 *
 * @author howxu <dev@howxu.cn>
 */
class ExtendedAEBridgeTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
        if (!ae2KeyTypesAreInitialized()) initializeAE2KeyTypes();
        bindTestAE2InterfaceItem();
        bindTestEntityType(InputInterfaceKind.INSTANCE);
        bindTestEntityType(StockingInterfaceKind.INSTANCE);
        bindTestEntityType(OutputInterfaceKind.INSTANCE);
        bindTestEntityType(AsyncOutputInterfaceKind.INSTANCE);
        bindTestEntityType(PatternInterfaceKind.INSTANCE);
    }

    @AfterEach
    void resetContributor() {
        ExtendedAEContributorBootstrap.resetForTesting();
    }

    @Test
    void unavailableExtendedAeContributorAddsNoPorts() {
        ExtendedAEContributor contributor = ExtendedAEContributorBootstrap.selectForTesting(false);

        assertThat(contributor.available()).isFalse();
        assertThat(contributor.portKinds()).isEmpty();
        assertThat(contributor.isPort("eae_me_extended_input_interface")).isFalse();
    }

    @Test
    void missingLoadedExtendedAeContributorAddsNoPorts() {
        ExtendedAEContributor contributor = ExtendedAEContributorBootstrap.selectForTesting(true);

        assertThat(contributor.available()).isFalse();
        assertThat(contributor.portKinds()).isEmpty();
    }

    @Test
    void unavailableContributorDoesNotChangeAe2BridgeBehavior() {
        ExtendedAEContributorBootstrap.installForTesting(new UnavailableTestContributor());
        LoadedAE2Bridge bridge = new LoadedAE2Bridge();

        assertThat(bridge.portKinds()).extracting(IOPortKind::id).containsExactly(
                "ae2_me_input_interface",
                "ae2_me_stocking_input_interface",
                "ae2_me_output_interface",
                "ae2_me_async_output_interface",
                "ae2_me_pattern_interface");
        assertThat(bridge.isPort("ae2_me_input_interface")).isTrue();
        assertThat(bridge.isPort("eae_me_extended_input_interface")).isFalse();
        assertThat(bridge.openMenu(null, LevelStub.create(Blocks.AIR, 1, 1, 1, BlockPos.ZERO), BlockPos.ZERO))
                .isFalse();
        assertThat(bridge.portOverlayTexture(InputInterfaceKind.INSTANCE))
                .isEqualTo(Identifier.fromNamespaceAndPath("mmcr", "block/appliedenergistics2/ae2_input"));
        assertThat(bridge.portOverlayTexture(null)).isNull();

        assertThatCode(() -> bridge.registerCapabilities(capabilityEvent())).doesNotThrowAnyException();

        List<Registration> registrations = new ArrayList<>();
        bridge.registerJadeCommon(commonRegistration(registrations));
        assertThat(registrations).hasSize(5);
    }

    @Test
    void availableContributorDoesNotOverrideNativeAe2Paths() {
        ExtendedAEContributorBootstrap.installForTesting(new AvailableTestContributor());
        LoadedAE2Bridge bridge = new LoadedAE2Bridge();

        assertThat(bridge.isPort("ae2_me_input_interface")).isTrue();
        assertThat(bridge.portOverlayTexture(InputInterfaceKind.INSTANCE))
                .isEqualTo(Identifier.fromNamespaceAndPath("mmcr", "block/appliedenergistics2/ae2_input"));
        var host = InputInterfaceKind.INSTANCE.entityFactory()
                .create(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState());
        Level level = LevelStub.createWithBlockEntities(List.of(host));
        host.setLevel(level);
        assertThatThrownBy(() -> bridge.openMenu(null, level, BlockPos.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Menus must be opened on the server.");
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

    private static IWailaCommonRegistration commonRegistration(List<Registration> registrations) {
        return (IWailaCommonRegistration) Proxy.newProxyInstance(
                IWailaCommonRegistration.class.getClassLoader(),
                new Class<?>[]{IWailaCommonRegistration.class},
                (_, method, args) -> {
                    if (method.getName().equals("registerBlockDataProvider")) {
                        registrations.add(new Registration(args[0], (Class<?>) args[1]));
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void bindTestEntityType(IOPortKind kind) {
        Identifier id = Identifier.fromNamespaceAndPath("mmcr", kind.id());
        MappedRegistry<BlockEntityType<?>> registry = (MappedRegistry<BlockEntityType<?>>) BuiltInRegistries.BLOCK_ENTITY_TYPE;
        registry.unfreeze(true);
        try {
            if (!registry.containsKey(id)) {
                Registry.register(registry, id, new BlockEntityType<>(kind.entityFactory(), Blocks.IRON_BLOCK));
            }
        } finally {
            registry.freeze();
        }
        ModBlockEntities.BES.put(kind.id(), DeferredHolder.create(Registries.BLOCK_ENTITY_TYPE, id));
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

    private static boolean ae2KeyTypesAreInitialized() {
        try {
            return !AEKeyTypes.getAll().isEmpty();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    /**
     * Captures a real Jade registration emitted by the bridge.
     *
     * @author howxu <dev@howxu.cn>
     */
    private record Registration(Object provider, Class<?> blockEntityType) {
    }

    /**
     * Fails if a bridge delegates work after declaring ExtendedAE unavailable.
     *
     * @author howxu <dev@howxu.cn>
     */
    private static final class UnavailableTestContributor implements ExtendedAEContributor {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public List<IOPortKind> portKinds() {
            throw unexpectedDelegation();
        }

        @Override
        public boolean isPort(String id) {
            throw unexpectedDelegation();
        }

        @Override
        public boolean openMenu(ServerPlayer player, Level level, BlockPos pos) {
            throw unexpectedDelegation();
        }

        @Override
        public @Nullable Identifier portOverlayTexture(IOPortKind kind) {
            throw unexpectedDelegation();
        }

        @Override
        public void registerCapabilities(RegisterCapabilitiesEvent event) {
            throw unexpectedDelegation();
        }

        @Override
        public void registerJadeCommon(IWailaCommonRegistration registration) {
            throw unexpectedDelegation();
        }

        private AssertionError unexpectedDelegation() {
            return new AssertionError("Unavailable ExtendedAE contributor must not be delegated to");
        }
    }

    /**
     * Fails if a native AE2 path is delegated to an available ExtendedAE contributor.
     *
     * @author howxu <dev@howxu.cn>
     */
    private static final class AvailableTestContributor implements ExtendedAEContributor {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<IOPortKind> portKinds() {
            return List.of();
        }

        @Override
        public boolean isPort(String id) {
            throw unexpectedDelegation();
        }

        @Override
        public boolean openMenu(ServerPlayer player, Level level, BlockPos pos) {
            throw unexpectedDelegation();
        }

        @Override
        public @Nullable Identifier portOverlayTexture(IOPortKind kind) {
            throw unexpectedDelegation();
        }

        @Override
        public void registerCapabilities(RegisterCapabilitiesEvent event) {
        }

        @Override
        public void registerJadeCommon(IWailaCommonRegistration registration) {
        }

        private AssertionError unexpectedDelegation() {
            return new AssertionError("Native AE2 paths must not be delegated to ExtendedAE");
        }
    }
}
