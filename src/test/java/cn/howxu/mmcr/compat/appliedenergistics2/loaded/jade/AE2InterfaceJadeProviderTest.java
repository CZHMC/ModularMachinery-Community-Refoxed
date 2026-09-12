package cn.howxu.mmcr.compat.appliedenergistics2.loaded.jade;

import appeng.api.networking.IGridNode;
import appeng.me.helpers.IGridConnectedBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.*;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.AsyncOutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.InputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.OutputInterfaceBlockEntity;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.tile.StockingInterfaceBlockEntity;
import cn.howxu.mmcr.internal.block.IOPortBlock;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Jade integration covers every MMCR AE2 interface host.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2InterfaceJadeProviderTest {

    @Test
    void registersTheSharedDataProviderForEveryAe2InterfaceHost() {
        List<Registration> registrations = new ArrayList<>();
        IWailaCommonRegistration registration = commonRegistration(registrations);

        new LoadedAE2Bridge().registerJadeCommon(registration);

        assertThat(registrations).extracting(Registration::hostType)
                .containsExactlyInAnyOrder(
                        InputInterfaceBlockEntity.class,
                        StockingInterfaceBlockEntity.class,
                        OutputInterfaceBlockEntity.class,
                        AsyncOutputInterfaceBlockEntity.class);
        assertThat(registrations).extracting(Registration::provider)
                .containsOnly(InterfaceJadeDataProvider.INSTANCE);
    }

    @Test
    void registersTheComponentProviderForIoPortBlocks() {
        List<Registration> registrations = new ArrayList<>();
        IWailaClientRegistration registration = clientRegistration(registrations);

        new LoadedAE2Bridge().registerJadeClient(registration);

        assertThat(registrations).containsExactly(
                new Registration(InterfaceJadeComponentProvider.INSTANCE, IOPortBlock.class));
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
}
