package cn.howxu.mmcr.compat.mekanism;

import cn.howxu.mmcr.api.capability.async.AsyncCapabilityOperation;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityPlanner;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilityRequest;
import cn.howxu.mmcr.api.capability.async.AsyncCapabilitySnapshot;
import cn.howxu.mmcr.api.capability.async.AsyncResourceAction;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.capability.CapabilityRequest;
import cn.howxu.mmcr.api.capability.CapabilityType;
import cn.howxu.mmcr.api.capability.CapabilityView;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.compat.mekanism.loaded.ChemicalPortCapability;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedMekanismBridge;
import cn.howxu.mmcr.test.TestBootstrap;
import cn.howxu.mmcr.util.IOType;
import mekanism.api.AutomationType;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalBuilder;
import mekanism.api.chemical.ChemicalResource;
import mekanism.api.chemical.IChemicalTank;
import mekanism.api.chemical.attribute.ChemicalAttributeValidator;
import mekanism.api.resource.LargeResourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the worker-safe planning boundary for Mekanism chemicals.
 *
 * @author howxu <dev@howxu.cn>
 */
class AsyncChemicalPlanningTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrapCapabilities();
    }

    @Test
    void chemical_requirement_plans_from_a_worker_safe_snapshot() throws Exception {
        ChemicalResource oxygen = ChemicalResource.of(registerChemical("async_oxygen"));
        ChemicalPortCapability capability = new ChemicalPortCapability(tank(oxygen, 1_000L), IOType.INPUT);
        AsyncPlanningFacet facet = capability.facet(AsyncPlanningFacet.class).orElseThrow();

        AsyncCapabilitySnapshot.Resource snapshot = (AsyncCapabilitySnapshot.Resource) capture(facet);
        AsyncCapabilityPlanner planner = workerPlanner(facet);
        AsyncCapabilityOperation operation = planner.plan(snapshot, new AsyncCapabilityRequest.Resource(
                capability.type().id(), 1L, List.of(new AsyncResourceAction(snapshot.slots().getFirst().resource().orElseThrow(),
                100L, false)))).orElseThrow();

        assertThat(operation).isInstanceOf(AsyncCapabilityOperation.Group.class);
        assertThat(((AsyncCapabilityOperation.Group) operation).operations())
                .containsExactly(new AsyncCapabilityOperation.Resource(capability.type().id(), 0,
                        snapshot.slots().getFirst().resource().orElseThrow(), 100L, false));
    }

    @Test
    void changed_chemical_tank_rejects_the_stale_async_intent_without_partial_consumption() throws Exception {
        ChemicalResource oxygen = ChemicalResource.of(registerChemical("stale_oxygen"));
        ChemicalResource hydrogen = ChemicalResource.of(registerChemical("stale_hydrogen"));
        FakeChemicalTank tank = tank(oxygen, 1_000L);
        ChemicalPortCapability capability = new ChemicalPortCapability(tank, IOType.INPUT);
        AsyncPlanningFacet facet = capability.facet(AsyncPlanningFacet.class).orElseThrow();
        AsyncCapabilitySnapshot.Resource snapshot = (AsyncCapabilitySnapshot.Resource) capture(facet);
        AsyncCapabilityOperation operation = workerPlanner(facet).plan(snapshot, new AsyncCapabilityRequest.Resource(
                capability.type().id(), 1L, List.of(new AsyncResourceAction(snapshot.slots().getFirst().resource().orElseThrow(),
                100L, false)))).orElseThrow();
        tank.setContents(hydrogen, 1_000L, null);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(commit(facet, operation, transaction).success()).isFalse();
        }
        assertThat(tank.resource()).isEqualTo(hydrogen);
        assertThat(tank.amountAsLong()).isEqualTo(1_000L);
    }

    @Test
    void heat_port_has_no_async_planning_facet() {
        LoadedMekanismBridge.HeatPort capability = new LoadedMekanismBridge.HeatPort() {
            private final CapabilityView view = new CapabilityView() {
                @Override
                public CapabilityType type() {
                    return new CapabilityType(MekanismRecipeTypes.HEAT);
                }

                @Override
                public CapabilityDirections directions() {
                    return CapabilityDirections.of(IOType.INPUT);
                }
            };

            @Override
            public mekanism.api.heat.IHeatHandler heatHandler() {
                return null;
            }

            @Override
            public CapabilityType type() {
                return view.type();
            }

            @Override
            public CapabilityDirections directions() {
                return view.directions();
            }

            @Override
            public CapabilityView view() {
                return view;
            }

            @Override
            public CapabilityOperation prepare(CapabilityRequest request) {
                throw new UnsupportedOperationException();
            }
        };

        assertThat(capability.facet(AsyncPlanningFacet.class)).isEmpty();
    }

    private static Object capture(AsyncPlanningFacet facet) throws Exception {
        return invoke(facet, "captureSnapshotOnServerThread");
    }

    private static AsyncCapabilityPlanner workerPlanner(AsyncPlanningFacet facet) throws Exception {
        return (AsyncCapabilityPlanner) invoke(facet, "workerPlannerOnServerThread");
    }

    private static CapabilityResult commit(AsyncPlanningFacet facet, AsyncCapabilityOperation operation,
                                           TransactionContext transaction) throws Exception {
        return (CapabilityResult) invoke(facet, "commitOnServerThread", AsyncCapabilityOperation.class,
                TransactionContext.class, operation, transaction);
    }

    private static Object invoke(AsyncPlanningFacet facet, String name, Class<?>... parameterTypes) throws Exception {
        Method method = AsyncPlanningFacet.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(facet);
    }

    private static Object invoke(AsyncPlanningFacet facet, String name, Class<?> first, Class<?> second,
                                 Object firstValue, Object secondValue) throws Exception {
        Method method = AsyncPlanningFacet.class.getDeclaredMethod(name, first, second);
        method.setAccessible(true);
        return method.invoke(facet, firstValue, secondValue);
    }

    private static Holder.Reference<Chemical> registerChemical(String path) {
        ResourceKey<Chemical> key = ResourceKey.create(MekanismAPI.CHEMICAL_REGISTRY_NAME,
                Identifier.fromNamespaceAndPath("mmcr_test", path));
        MappedRegistry<Chemical> registry = (MappedRegistry<Chemical>) MekanismAPI.CHEMICAL_REGISTRY;
        return registry.get(key).orElseGet(() -> {
            registry.unfreeze(true);
            Registry.register(registry, key.identifier(), new Chemical(ChemicalBuilder.builder()));
            registry.freeze();
            return registry.get(key).orElseThrow();
        });
    }

    private static FakeChemicalTank tank(ChemicalResource resource, long amount) {
        FakeChemicalTank tank = new FakeChemicalTank(1_000L);
        tank.setContents(resource, amount, null);
        return tank;
    }

    private static final class FakeChemicalTank implements IChemicalTank {
        private final long capacity;
        private ChemicalResource resource = ChemicalResource.EMPTY;
        private long amount;

        private FakeChemicalTank(long capacity) {
            this.capacity = capacity;
        }

        @Override
        public LargeResourceStack<ChemicalResource> asStack() {
            return new LargeResourceStack<>(resource, amount);
        }

        @Override
        public int insert(ChemicalResource resource, int amount, TransactionContext transaction,
                          AutomationType automationType) {
            if (!isValid(resource) || (!this.resource.isEmpty() && !this.resource.equals(resource))) return 0;
            int moved = (int) Math.min(amount, capacity - this.amount);
            setContents(resource, this.amount + moved, transaction);
            return moved;
        }

        @Override
        public int extract(ChemicalResource resource, int amount, TransactionContext transaction,
                           AutomationType automationType) {
            if (!this.resource.equals(resource)) return 0;
            int moved = (int) Math.min(amount, this.amount);
            setContents(this.resource, this.amount - moved, transaction);
            return moved;
        }

        @Override
        public long capacityAsLong(ChemicalResource resource) {
            return capacity;
        }

        @Override
        public boolean isValid(ChemicalResource resource) {
            return !resource.isEmpty();
        }

        @Override
        public void setContents(LargeResourceStack<ChemicalResource> contents, TransactionContext transaction) {
            resource = contents.resource();
            amount = contents.amount();
        }

        @Override
        public LargeResourceStack.StackHelper<ChemicalResource> stackHelper() {
            return LargeResourceStack.CHEMICAL_HELPER;
        }

        @Override
        public ChemicalAttributeValidator getAttributeValidator() {
            return ChemicalAttributeValidator.ALWAYS_ALLOW;
        }
    }
}
