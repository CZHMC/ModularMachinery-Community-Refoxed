package cn.howxu.mmcr.compat.mekanism;

import cn.howxu.mmcr.api.capability.async.AsyncCapabilityOperation;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityPlanner;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityRequest;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import cn.howxu.mmcr.api.capability.storage.LongValueStorage;
import cn.howxu.mmcr.compat.mekanism.loaded.HeatPortCapability;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import mekanism.api.heat.IHeatCapacitor;
import mekanism.common.capabilities.heat.BasicHeatCapacitor;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies worker-safe Mekanism heat planning and commit behavior.
 *
 * @author howxu <dev@howxu.cn>
 */
class HeatCapabilityTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrapCapabilities();
    }

    @Test
    void heat_capability_plans_temperature_checks_and_output_heat() throws Exception {
        AtomicReference<Double> heat = new AtomicReference<>(1_000D);
        IHeatCapacitor capacitor = capacitor(heat, 100D);
        HeatPortCapability capability = new HeatPortCapability(capacitor, IOType.OUTPUT);
        AsyncPlanningFacet facet = capability.facet(AsyncPlanningFacet.class).orElseThrow();
        AsyncCapabilitySnapshot.Heat snapshot = (AsyncCapabilitySnapshot.Heat) invoke(facet,
                "captureSnapshotOnServerThread");
        AsyncCapabilityPlanner planner = (AsyncCapabilityPlanner) invoke(facet, "workerPlannerOnServerThread");

        assertThat(planner.plan(snapshot, new AsyncCapabilityRequest.Heat(capability.type().id(), 1L,
                9D, true, 9L))).isPresent();
        AsyncCapabilityOperation operation = planner.plan(snapshot, new AsyncCapabilityRequest.Heat(
                capability.type().id(), 1L, 50D, false, 50L)).orElseThrow();
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(invokeCommit(facet, operation, transaction)).isTrue();
            transaction.commit();
        }

        assertThat(heat.get()).isEqualTo(1_050D);
    }

    @Test
    void prepared_temperature_check_reads_live_heat_on_every_commit_without_consuming_it() throws Exception {
        BasicHeatCapacitor capacitor = BasicHeatCapacitor.create(100D, () -> 10D, null);
        HeatPortCapability capability = new HeatPortCapability(capacitor, IOType.INPUT);
        AsyncPlanningFacet facet = capability.facet(AsyncPlanningFacet.class).orElseThrow();
        AsyncCapabilitySnapshot.Heat snapshot = (AsyncCapabilitySnapshot.Heat) invoke(facet,
                "captureSnapshotOnServerThread");
        AsyncCapabilityPlanner planner = (AsyncCapabilityPlanner) invoke(facet, "workerPlannerOnServerThread");
        AsyncCapabilityOperation operation = planner.plan(snapshot, new AsyncCapabilityRequest.Heat(
                capability.type().id(), 4L, 9D, true, 9L)).orElseThrow();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(invokeCommit(facet, operation, transaction)).isTrue();
            transaction.commit();
        }
        assertThat(capacitor.getHeat()).isEqualTo(1_000D);

        try (Transaction transaction = Transaction.openRoot()) {
            capacitor.handleHeat(-200D, transaction);
            transaction.commit();
        }
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(invokeCommit(facet, operation, transaction)).isFalse();
            transaction.commit();
        }
        assertThat(snapshot.temperature()).isEqualTo(10D);
        assertThat(capacitor.getHeat()).isEqualTo(800D);

        try (Transaction transaction = Transaction.openRoot()) {
            capacitor.handleHeat(100D, transaction);
            transaction.commit();
        }
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(invokeCommit(facet, operation, transaction)).isTrue();
            transaction.commit();
        }
        assertThat(capacitor.getHeat()).isEqualTo(900D);
    }

    @Test
    void heat_output_and_energy_share_the_callers_transaction_rollback() throws Exception {
        BasicHeatCapacitor capacitor = BasicHeatCapacitor.create(100D, () -> 10D, null);
        HeatPortCapability capability = new HeatPortCapability(capacitor, IOType.OUTPUT);
        AsyncPlanningFacet facet = capability.facet(AsyncPlanningFacet.class).orElseThrow();
        AsyncCapabilityOperation operation = new AsyncCapabilityOperation.Heat(capability.type().id(),
                50D, false, 50L);
        LongValueStorage energy = new LongValueStorage(100L, 100L, null);
        energy.setAmount(100L);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(energy.extract(7L, transaction)).isEqualTo(7L);
            try (Transaction nested = Transaction.open(transaction)) {
                assertThat(invokeCommit(facet, operation, nested)).isTrue();
                nested.commit();
            }
            assertThat(capacitor.getHeat()).isEqualTo(1_050D);
        }
        assertThat(capacitor.getHeat()).isEqualTo(1_000D);
        assertThat(energy.amount()).isEqualTo(100L);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(energy.extract(7L, transaction)).isEqualTo(7L);
            assertThat(invokeCommit(facet, operation, transaction)).isTrue();
            transaction.commit();
        }
        assertThat(capacitor.getHeat()).isEqualTo(1_050D);
        assertThat(energy.amount()).isEqualTo(93L);
    }

    private static IHeatCapacitor capacitor(AtomicReference<Double> heat, double capacity) {
        return (IHeatCapacitor) Proxy.newProxyInstance(IHeatCapacitor.class.getClassLoader(),
                new Class<?>[]{IHeatCapacitor.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getHeat" -> heat.get();
                    case "getTemperature" -> heat.get() / capacity;
                    case "getHeatCapacity" -> capacity;
                    case "handleHeat" -> {
                        heat.set(heat.get() + (double) arguments[0]);
                        yield null;
                    }
                    case "toString" -> "TestHeatCapacitor";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> method.getReturnType() == boolean.class ? false
                            : method.getReturnType() == int.class ? 0
                            : method.getReturnType() == long.class ? 0L
                            : method.getReturnType() == double.class ? 0D : null;
                });
    }

    private static Object invoke(AsyncPlanningFacet facet, String name) throws Exception {
        Method method = AsyncPlanningFacet.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(facet);
    }

    private static boolean invokeCommit(AsyncPlanningFacet facet, AsyncCapabilityOperation operation,
                                        Transaction transaction) throws Exception {
        Method method = AsyncPlanningFacet.class.getDeclaredMethod("commitOnServerThread",
                AsyncCapabilityOperation.class,
                net.neoforged.neoforge.transfer.transaction.TransactionContext.class);
        method.setAccessible(true);
        return ((cn.howxu.mmcr.api.capability.plan.CapabilityResult) method.invoke(facet, operation, transaction)).success();
    }
}
