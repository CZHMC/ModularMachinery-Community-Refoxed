package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import cn.howxu.mmcr.internal.tile.MachineControllerRuntime;
import cn.howxu.mmcr.internal.tile.StructureRuntime;
import cn.howxu.mmcr.test.RuntimeTestFixtures;
import cn.howxu.mmcr.test.TestBootstrap;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the final controller-owned structure runtime contract.
 *
 * @author howxu <dev@howxu.cn>
 */
class StructureRuntimeTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void newRuntimeStartsUnformedAndDirty() throws Exception {
        MachineControllerBlockEntity controller = controller();

        assertThat(controller.structureSnapshot().formed()).isFalse();
        assertThat(controller.structureSnapshot().version()).isEqualTo(1L);
        assertThat(controller.structureSnapshot().dirty()).isTrue();
    }

    @Test
    void invalidationRequestKeepsThePublishedStructureSnapshotImmutable() throws Exception {
        MachineControllerBlockEntity controller = controller();

        controller.onStructureBlockChanged(controller.getBlockPos().offset(1, 0, 0));
        StructureSnapshot snapshot = controller.structureSnapshot();

        assertThat(snapshot.dirty()).isTrue();
        assertThat(snapshot.criticalChunks()).isUnmodifiable();
        assertThat(snapshot.criticalChunks()).containsExactlyInAnyOrderElementsOf(Set.of());
    }

    @Test
    void structureBoundaryRequiresLevelAndControllerPosition() throws Exception {
        MachineControllerBlockEntity controller = controller();

        assertThatThrownBy(() -> controller.tickStructure(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.handleStructureChunkChanged(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedCheckRequestsDoNotInventAFormationVersion() throws Exception {
        MachineControllerBlockEntity controller = controller();
        long configuredVersion = controller.structureSnapshot().version();

        controller.onStructureBlockChanged(controller.getBlockPos().offset(1, 0, 0));
        controller.onStructureBlockChanged(controller.getBlockPos().offset(1, 0, 0));

        assertThat(controller.structureSnapshot().version()).isEqualTo(configuredVersion);
    }

    @Test
    void clearScanResetsScanSteppedTick() throws Exception {
        MachineControllerBlockEntity controller = controller();
        StructureRuntime runtime = runtimeOf(controller);
        invoke(runtime, "setScanSteppedTick", 123L);

        long steppedBefore = readScanSteppedTick(controller);
        assertThat(steppedBefore).isEqualTo(123L);

        invoke(runtime, "clearScan");

        assertThat(readScan(controller)).isNull();
        assertThat(readScanSteppedTick(controller)).isEqualTo(Long.MIN_VALUE);
    }

    private static StructureRuntime runtimeOf(MachineControllerBlockEntity controller) throws Exception {
        java.lang.reflect.Field runtimeField = MachineControllerBlockEntity.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        MachineControllerRuntime runtime = (MachineControllerRuntime) runtimeField.get(controller);
        java.lang.reflect.Field structureField = MachineControllerRuntime.class.getDeclaredField("structure");
        structureField.setAccessible(true);
        return (StructureRuntime) structureField.get(runtime);
    }

    private static void invoke(Object target, String methodName, Object... args) throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i] == null ? null : args[i].getClass();
            if (args[i] instanceof Long) paramTypes[i] = long.class;
        }
        java.lang.reflect.Method method = target.getClass().getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static Object readScan(MachineControllerBlockEntity controller) throws Exception {
        Object work = workSnapshot(controller);
        java.lang.reflect.Method scanMethod = work.getClass().getDeclaredMethod("scan");
        scanMethod.setAccessible(true);
        return scanMethod.invoke(work);
    }

    private static long readScanSteppedTick(MachineControllerBlockEntity controller) throws Exception {
        Object work = workSnapshot(controller);
        java.lang.reflect.Method tickMethod = work.getClass().getDeclaredMethod("scanSteppedTick");
        tickMethod.setAccessible(true);
        return (long) tickMethod.invoke(work);
    }

    private static Object workSnapshot(MachineControllerBlockEntity controller) throws Exception {
        java.lang.reflect.Method m = MachineControllerBlockEntity.class
                .getDeclaredMethod("structureWorkSnapshotForTesting");
        m.setAccessible(true);
        return m.invoke(controller);
    }

    private static MachineControllerBlockEntity controller() {
        return RuntimeTestFixtures.controller(MMCR.id("test_cube"));
    }
}
