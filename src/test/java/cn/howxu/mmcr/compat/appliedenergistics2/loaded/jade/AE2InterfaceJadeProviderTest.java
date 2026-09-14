package cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade;

import appeng.api.networking.IGridNode;
import appeng.me.helpers.IGridConnectedBlockEntity;
import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.CapabilityHost;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.capability.CapabilityRequest;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.CapabilityType;
import cn.howxu.mmcr.api.capability.CapabilityView;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.facet.CapabilityFacet;
import cn.howxu.mmcr.api.capability.facet.PresentationFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.presentation.CapabilityDisplay;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.*;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.AsyncOutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.PatternInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import cn.howxu.mmcr.internal.block.IOPortBlock;
import cn.howxu.mmcr.util.IOType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Jade integration covers every MMCR AE2 interface host.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2InterfaceJadeProviderTest {

    @Test
    void registersTheComponentProviderForIoPortBlocks() {
        List<Registration> registrations = new ArrayList<>();
        IWailaClientRegistration registration = clientRegistration(registrations);

        new LoadedAE2Bridge().registerJadeClient(registration);

        assertThat(registrations).containsExactly(
                new Registration(InterfaceJadeComponentProvider.INSTANCE, IOPortBlock.class));
    }

    @Test
    void registersThePatternHostWithTheCommonGridDataProvider() {
        List<Registration> registrations = new ArrayList<>();

        new LoadedAE2Bridge().registerJadeCommon(commonRegistration(registrations));

        assertThat(registrations).contains(new Registration(InterfaceJadeDataProvider.INSTANCE,
                PatternInterfaceBlockEntity.class));
    }

    @Test
    void componentProviderAcceptsAnyGridConnectedAe2Host() {
        CompoundTag serverData = new CompoundTag();
        serverData.putByte(InterfaceJadeDataProvider.STATE, (byte) 3);
        List<Component> added = new ArrayList<>();
        IGridConnectedBlockEntity host = gridHost();

        InterfaceJadeComponentProvider.INSTANCE.appendTooltip(
                tooltip(added), accessor(host, serverData), null);

        assertThat(added).hasSize(1);
    }

    @Test
    void dataProviderReadsTheActionableNodeFromACommonGridHost() {
        CompoundTag serverData = new CompoundTag();
        IGridConnectedBlockEntity host = gridHost();

        InterfaceJadeDataProvider.INSTANCE.appendServerData(
                serverData, accessor(host, new CompoundTag()));

        assertThat(serverData.getByteOr(InterfaceJadeDataProvider.STATE, (byte) 0))
                .isEqualTo((byte) 3);
    }

    @Test
    void componentProviderRendersOnlyNonEmptyOutputCapabilityContents() {
        CompoundTag serverData = new CompoundTag();
        serverData.putByte(InterfaceJadeDataProvider.STATE, (byte) 3);
        List<Component> added = new ArrayList<>();
        Object host = capabilityGridHost();

        InterfaceJadeComponentProvider.INSTANCE.appendTooltip(
                tooltip(added), accessor(host, serverData), null);

        assertThat(added).contains(
                Component.translatable("gui.mmcr.port.fluids").append(Component.literal(" 1,000 mB")));
        assertThat(added).doesNotContain(Component.translatable("gui.mmcr.port.items")
                .append(Component.literal(" 4")));
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

    private static IWailaClientRegistration clientRegistration(List<Registration> registrations) {
        return (IWailaClientRegistration) Proxy.newProxyInstance(
                IWailaClientRegistration.class.getClassLoader(),
                new Class<?>[]{IWailaClientRegistration.class},
                (_, method, args) -> {
                    if (method.getName().equals("registerBlockComponent")) {
                        registrations.add(new Registration(args[0], (Class<?>) args[1]));
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static IGridConnectedBlockEntity gridHost() {
        IGridNode node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[]{IGridNode.class},
                (_, method, _) -> switch (method.getName()) {
                    case "isPowered", "hasGridBooted", "meetsChannelRequirements" -> true;
                    default -> defaultValue(method.getReturnType());
                });
        return (IGridConnectedBlockEntity) Proxy.newProxyInstance(
                IGridConnectedBlockEntity.class.getClassLoader(),
                new Class<?>[]{IGridConnectedBlockEntity.class},
                (_, method, _) -> method.getName().equals("getActionableNode")
                        ? node : defaultValue(method.getReturnType()));
    }

    private static Object capabilityGridHost() {
        List<MachineCapability> capabilities = List.of(
                new PresentedCapability("item", "4", "item", IOType.INPUT),
                new PresentedCapability("fluid", "1,000", "mB", IOType.OUTPUT));
        IGridNode node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[]{IGridNode.class},
                (_, method, _) -> switch (method.getName()) {
                    case "isPowered", "hasGridBooted", "meetsChannelRequirements" -> true;
                    default -> defaultValue(method.getReturnType());
                });
        return Proxy.newProxyInstance(
                IGridConnectedBlockEntity.class.getClassLoader(),
                new Class<?>[]{IGridConnectedBlockEntity.class, CapabilityHost.class},
                (_, method, _) -> switch (method.getName()) {
                    case "getActionableNode" -> node;
                    case "capabilitySnapshot" -> new CapabilitySnapshot(capabilities);
                    case "capabilities" -> capabilities;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static BlockAccessor accessor(Object target, CompoundTag serverData) {
        return (BlockAccessor) Proxy.newProxyInstance(
                BlockAccessor.class.getClassLoader(),
                new Class<?>[]{BlockAccessor.class},
                (_, method, _) -> switch (method.getName()) {
                    case "getTarget" -> target;
                    case "getServerData" -> serverData;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ITooltip tooltip(List<Component> added) {
        return (ITooltip) Proxy.newProxyInstance(
                ITooltip.class.getClassLoader(),
                new Class<?>[]{ITooltip.class},
                (_, method, args) -> {
                    if (method.getName().equals("add") && args != null && args.length == 1
                            && args[0] instanceof Component component) {
                        added.add(component);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private record Registration(Object provider, Class<?> hostType) {
    }

    private static final class PresentedCapability implements MachineCapability, PresentationFacet {
        private final CapabilityType type;
        private final CapabilityDirections directions;
        private final CapabilityView view;
        private final List<CapabilityDisplay> displays;

        private PresentedCapability(String label, String value, String unit, IOType direction) {
            type = new CapabilityType(MMCR.id(label));
            directions = CapabilityDirections.of(direction);
            view = new CapabilityView() {
                @Override
                public CapabilityType type() {
                    return PresentedCapability.this.type;
                }

                @Override
                public CapabilityDirections directions() {
                    return PresentedCapability.this.directions;
                }

                @Override
                public Set<Class<? extends CapabilityFacet>> facets() {
                    return Set.of(PresentationFacet.class);
                }
            };
            displays = List.of(new CapabilityDisplay(label, value, unit, Optional.empty()));
        }

        @Override
        public CapabilityType type() {
            return type;
        }

        @Override
        public CapabilityDirections directions() {
            return directions;
        }

        @Override
        public CapabilityView view() {
            return view;
        }

        @Override
        public CapabilityOperation prepare(CapabilityRequest request) {
            throw new UnsupportedOperationException("Not used for Jade display tests");
        }

        @Override
        public List<CapabilityDisplay> displays(CapabilityView ignored) {
            return displays;
        }
    }
}
