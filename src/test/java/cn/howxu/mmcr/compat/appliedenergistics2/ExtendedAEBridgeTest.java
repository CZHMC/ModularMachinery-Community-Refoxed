package cn.howxu.mmcr.compat.appliedenergistics2;

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
import net.minecraft.server.level.ServerPlayer;
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

class ExtendedAEBridgeTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
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

    private record Registration(Object provider, Class<?> blockEntityType) {
    }

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
}
