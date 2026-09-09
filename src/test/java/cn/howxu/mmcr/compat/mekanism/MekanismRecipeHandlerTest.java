package cn.howxu.mmcr.compat.mekanism;

import cn.howxu.mmcr.api.capability.CapabilityRequest;
import cn.howxu.mmcr.api.capability.CapabilityDirections;
import cn.howxu.mmcr.api.capability.CapabilityType;
import cn.howxu.mmcr.api.capability.CapabilityView;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.plan.CapabilityOperation;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.plan.PlanningContext;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.api.capability.plan.RequirementPlan;
import cn.howxu.mmcr.api.capability.status.FailureReasonRegistry;
import cn.howxu.mmcr.api.compat.mekanism.ChemicalIngredient;
import cn.howxu.mmcr.api.compat.mekanism.HeatRequirement;
import cn.howxu.mmcr.api.compat.mekanism.MekanismFailureReasons;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandler;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandlerRegistry;
import cn.howxu.mmcr.api.recipe.requirement.RequirementType;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedChemicalRequirement;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedChemicalOutput;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedHeatRequirement;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedHeatOutput;
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
import mekanism.api.datamaps.chemical.attribute.IChemicalAttribute;
import mekanism.api.heat.IHeatHandler;
import mekanism.api.resource.LargeResourceStack;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies optional Mekanism recipe handler planning behavior.
 *
 * @author howxu <dev@howxu.cn>
 */
class MekanismRecipeHandlerTest {
    private RequirementHandlerRegistry.TestScope requirementScope;
    private OutputRegistry.TestScope outputScope;

    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
        if (FailureReasonRegistry.find(MekanismFailureReasons.MEKANISM_UNAVAILABLE.id()) == null) {
            MekanismBridgeBootstrap.bootstrap();
        }
    }

    @BeforeEach
    void openRegistryScope() {
        requirementScope = RequirementHandlerRegistry.openTestScope();
        outputScope = OutputRegistry.openTestScope();
    }

    @AfterEach
    void resetBridge() {
        outputScope.close();
        requirementScope.close();
        MekanismBridgeBootstrap.resetForTesting();
    }

    @Test
    void recipe_types_only_contains_the_bridge_registration_entry_point() {
        assertThat(Arrays.stream(MekanismRecipeTypes.class.getDeclaredMethods())
                .map(Method::getName))
                .containsExactly("register");
    }

    @Test
    void unavailable_chemical_input_is_blocked_with_registered_reason() {
        MekanismBridgeBootstrap.installForTesting(MekanismBridgeBootstrap.selectForTesting(false));
        LoadedChemicalRequirement.installUnavailableHandler();

        LoadedChemicalRequirement requirement = LoadedChemicalRequirement.input(
                ChemicalIngredient.chemical(Identifier.parse("mekanism:oxygen"), 1_000));
        RequirementPlan result = chemicalHandler().plan(requirement, List.of(), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().details().get("reason"))
                .isEqualTo(MekanismFailureReasons.MEKANISM_UNAVAILABLE.id().toString());
    }

    @Test
    void unavailable_bridge_registers_without_linking_loaded_classes() throws Exception {
        ClassLoader parent = MekanismRecipeTypes.class.getClassLoader();
        ClassLoader isolated = new ClassLoader(parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("cn.howxu.mmcr.compat.mekanism.loaded.")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.equals("cn.howxu.mmcr.compat.mekanism.MekanismRecipeTypes")
                        || name.equals("cn.howxu.mmcr.compat.mekanism.UnavailableMekanismBridge")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            byte[] bytes;
                            try (var stream = MekanismRecipeTypes.class.getResourceAsStream(
                                    "/" + name.replace('.', '/') + ".class")) {
                                if (stream == null) throw new ClassNotFoundException(name);
                                bytes = stream.readAllBytes();
                            } catch (java.io.IOException exception) {
                                throw new ClassNotFoundException(name, exception);
                            }
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };

        Class<?> bridgeClass = Class.forName(
                "cn.howxu.mmcr.compat.mekanism.UnavailableMekanismBridge", true, isolated);
        Field instance = bridgeClass.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        MekanismBridge bridge = (MekanismBridge) instance.get(null);

        bridge.registerRecipeTypes(MekanismRecipeTypes.CHEMICAL, MekanismRecipeTypes.HEAT_TEMPERATURE,
                MekanismRecipeTypes.HEAT);
    }

    @Test
    void unavailable_registration_keeps_identifier_codecs_canonical() {
        MekanismBridgeBootstrap.installForTesting(MekanismBridgeBootstrap.selectForTesting(false));
        MekanismRecipeTypes.register();

        assertThat(RequirementHandlerRegistry.typeFor(MekanismRecipeTypes.CHEMICAL)).isNotNull();
        assertThat(RequirementHandlerRegistry.typeFor(MekanismRecipeTypes.HEAT_TEMPERATURE)).isNotNull();
        assertThat(RequirementHandlerRegistry.typeFor(MekanismRecipeTypes.HEAT)).isNotNull();
        assertThat(OutputRegistry.typeFor(MekanismRecipeTypes.CHEMICAL)).isNotNull();
        assertThat(OutputRegistry.typeFor(MekanismRecipeTypes.HEAT)).isNotNull();

        JsonObject payload = new JsonObject();
        payload.addProperty("type", MekanismRecipeTypes.CHEMICAL.toString());
        payload.addProperty("kind", "chemical");
        payload.addProperty("id", "mekanism:oxygen");
        payload.addProperty("amount", 1_000L);
        payload.addProperty("io", "input");
        MachineRequirement requirement = MachineRequirement.CODEC.parse(JsonOps.INSTANCE, payload).getOrThrow();
        @SuppressWarnings("unchecked")
        RequirementHandler<MachineRequirement> handler = (RequirementHandler<MachineRequirement>)
                RequirementHandlerRegistry.handlerFor(requirement.type());

        RequirementPlan result = handler.plan(requirement, List.of(), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().details().get("reason"))
                .isEqualTo(MekanismFailureReasons.MEKANISM_UNAVAILABLE.id().toString());
        assertThat(OutputRegistry.fromRequirement(requirement)).isNull();
    }

    @Test
    void loaded_registration_installs_loaded_canonical_requirement_types() {
        MekanismBridgeBootstrap.installForTesting(MekanismBridgeBootstrap.selectForTesting(true));
        MekanismRecipeTypes.register();

        assertThat(RequirementHandlerRegistry.typeFor(MekanismRecipeTypes.CHEMICAL))
                .isSameAs(LoadedChemicalRequirement.TYPE);
        assertThat(RequirementHandlerRegistry.typeFor(MekanismRecipeTypes.HEAT_TEMPERATURE))
                .isSameAs(LoadedHeatRequirement.TEMPERATURE_TYPE);
        assertThat(RequirementHandlerRegistry.typeFor(MekanismRecipeTypes.HEAT))
                .isSameAs(LoadedHeatRequirement.HEAT_TYPE);
        assertThat(OutputRegistry.typeFor(MekanismRecipeTypes.CHEMICAL))
                .isSameAs(LoadedChemicalOutput.TYPE);
        assertThat(OutputRegistry.typeFor(MekanismRecipeTypes.HEAT))
                .isSameAs(LoadedHeatOutput.TYPE);

        JsonObject payload = new JsonObject();
        payload.addProperty("type", MekanismRecipeTypes.CHEMICAL.toString());
        payload.addProperty("id", "mekanism:oxygen");
        payload.addProperty("amount", 1_000L);
        MachineRequirement requirement = MachineRequirement.CODEC.parse(JsonOps.INSTANCE, payload).getOrThrow();
        assertThat(requirement).isInstanceOf(LoadedChemicalRequirement.class);
        assertThat(requirement.type()).isSameAs(LoadedChemicalRequirement.TYPE);

        JsonObject outputPayload = new JsonObject();
        outputPayload.addProperty("type", MekanismRecipeTypes.CHEMICAL.toString());
        outputPayload.addProperty("id", "mekanism:oxygen");
        outputPayload.addProperty("amount", 1_000L);
        MachineOutput output = MachineOutput.CODEC.parse(JsonOps.INSTANCE, outputPayload).getOrThrow();
        assertThat(output.outputType()).isSameAs(LoadedChemicalOutput.TYPE);
    }

    @Test
    void minimum_temperature_input_does_not_mutate_heat() {
        MekanismBridgeBootstrap.installForTesting(MekanismBridgeBootstrap.selectForTesting(true));
        LoadedHeatRequirement.installHandler(LoadedMekanismBridge.heatHandler());
        FakeHeatPort port = new FakeHeatPort(360D);

        LoadedHeatRequirement requirement = LoadedHeatRequirement.minimumTemperature(350D);
        RequirementPlan result = heatHandler().plan(requirement, List.of(port), testContext());

        assertThat(result.successful()).isTrue();
        assertThat(port.temperature()).isEqualTo(360D);
        assertThat(port.handledHeat()).isEqualTo(0D);
    }

    @Test
    void non_radioactive_attribute_rejection_reports_output_blocked() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("attribute_rejected");
        ChemicalAttributeValidator rejectAttributes = new ChemicalAttributeValidator() {
            @Override
            public boolean validate(IChemicalAttribute attribute) {
                return false;
            }

            @Override
            public boolean process(Chemical value) {
                return false;
            }
        };
        FakeChemicalTank tank = new FakeChemicalTank(1_000L, rejectAttributes);
        FakeChemicalPort port = new FakeChemicalPort(tank, IOType.OUTPUT);

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.output(chemical.key().identifier(), 100L, 1F),
                List.of(port), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().reason()).isSameAs(MekanismFailureReasons.CHEMICAL_OUTPUT_BLOCKED);
    }

    @Test
    void exact_chemical_input_is_reserved_and_extracted_transactionally() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("exact_input");
        FakeChemicalTank tank = new FakeChemicalTank(2_000L, ChemicalAttributeValidator.ALWAYS_ALLOW);
        ChemicalResource resource = ChemicalResource.of(chemical);
        tank.setContents(resource, 1_000L, null);
        FakeChemicalPort port = new FakeChemicalPort(tank, IOType.INPUT);

        RequirementPlan planned = chemicalHandler().plan(
                LoadedChemicalRequirement.input(
                        ChemicalIngredient.chemical(chemical.key().identifier(), 1_000L)),
                List.of(port), testContext());
        RequirementPlan materialized = planned.materialize(1, new PlanningReservations(), null);

        assertThat(materialized.successful()).isTrue();
        try (Transaction transaction = Transaction.open(null)) {
            CapabilityResult result = materialized.operations().getFirst().commit(transaction);
            assertThat(result.success()).isTrue();
            transaction.commit();
        }
        assertThat(tank.amount()).isZero();
    }

    @Test
    void unknown_exact_chemical_is_a_type_mismatch() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.input(
                        ChemicalIngredient.chemical(Identifier.parse("mekanism:not_registered"), 1L)),
                List.of(), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().reason()).isSameAs(MekanismFailureReasons.CHEMICAL_TYPE_MISMATCH);
    }

    @Test
    void registered_exact_chemical_input_without_matching_ports_reports_insufficient_resource() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("missing_input");

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.input(
                        ChemicalIngredient.chemical(chemical.key().identifier(), 1L)),
                List.of(), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().details().get("reason")).isEqualTo("insufficient_resource");
    }

    @Test
    void chemical_input_with_only_output_direction_ports_reports_insufficient_resource() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("output_only");
        FakeChemicalTank tank = new FakeChemicalTank(2_000L, ChemicalAttributeValidator.ALWAYS_ALLOW);
        tank.setContents(ChemicalResource.of(chemical), 1_000L, null);

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.input(ChemicalIngredient.chemical(chemical.key().identifier(), 1L)),
                List.of(new FakeChemicalPort(tank, IOType.OUTPUT)), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().details().get("reason")).isEqualTo("insufficient_resource");
    }

    @Test
    void radioactive_chemical_rejection_reports_radioactivity() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("radioactive", true);
        ChemicalAttributeValidator rejectAttributes = new ChemicalAttributeValidator() {
            @Override
            public boolean validate(IChemicalAttribute attribute) {
                return false;
            }

            @Override
            public boolean process(Chemical value) {
                return false;
            }
        };
        FakeChemicalPort port = new FakeChemicalPort(
                new FakeChemicalTank(1_000L, rejectAttributes), IOType.OUTPUT, true);

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.output(chemical.key().identifier(), 1L, 1F),
                List.of(port), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().reason()).isSameAs(MekanismFailureReasons.CHEMICAL_RADIOACTIVITY_REJECTED);
    }

    @Test
    void chemical_port_default_radioactive_flag_is_false() {
        FakeChemicalPort port = new FakeChemicalPort(
                new FakeChemicalTank(1_000L, ChemicalAttributeValidator.ALWAYS_ALLOW), IOType.OUTPUT);

        assertThat(port.radioactive()).isFalse();
    }

    @Test
    void radioactive_chemical_output_with_only_normal_ports_reports_missing_output() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("missing_radioactive_output", true);
        FakeChemicalPort port = new FakeChemicalPort(
                new FakeChemicalTank(1_000L, ChemicalAttributeValidator.ALWAYS_ALLOW), IOType.OUTPUT, false);

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.output(chemical.key().identifier(), 1L, 1F),
                List.of(port), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().details().get("reason")).isEqualTo("no_output_capacity");
    }

    @Test
    void normal_chemical_output_with_only_radioactive_ports_reports_missing_output() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("missing_normal_output", false);
        FakeChemicalPort port = new FakeChemicalPort(
                new FakeChemicalTank(1_000L, ChemicalAttributeValidator.ALWAYS_ALLOW), IOType.OUTPUT, true);

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.output(chemical.key().identifier(), 1L, 1F),
                List.of(port), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().details().get("reason")).isEqualTo("no_output_capacity");
    }

    @Test
    void radioactive_chemical_input_with_only_normal_ports_reports_insufficient_resource() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("missing_radioactive_input", true);
        FakeChemicalPort port = new FakeChemicalPort(
                new FakeChemicalTank(1_000L, ChemicalAttributeValidator.ALWAYS_ALLOW), IOType.INPUT, false);

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.input(ChemicalIngredient.chemical(chemical.key().identifier(), 1L)),
                List.of(port), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().details().get("reason")).isEqualTo("insufficient_resource");
    }

    @Test
    void normal_chemical_input_with_only_radioactive_ports_reports_insufficient_resource() {
        LoadedChemicalRequirement.installHandler(LoadedMekanismBridge.chemicalHandler());
        Holder.Reference<Chemical> chemical = registerChemical("missing_normal_input", false);
        FakeChemicalPort port = new FakeChemicalPort(
                new FakeChemicalTank(1_000L, ChemicalAttributeValidator.ALWAYS_ALLOW), IOType.INPUT, true);

        RequirementPlan result = chemicalHandler().plan(
                LoadedChemicalRequirement.input(ChemicalIngredient.chemical(chemical.key().identifier(), 1L)),
                List.of(port), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().details().get("reason")).isEqualTo("insufficient_resource");
    }

    @Test
    void insufficient_temperature_reports_registered_reason() {
        LoadedHeatRequirement.installHandler(LoadedMekanismBridge.heatHandler());

        RequirementPlan result = heatHandler().plan(
                LoadedHeatRequirement.minimumTemperature(350D), List.of(new FakeHeatPort(300D)), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().reason()).isSameAs(MekanismFailureReasons.HEAT_TEMPERATURE_INSUFFICIENT);
    }

    @Test
    void minimum_temperature_ignores_output_only_ports() {
        LoadedHeatRequirement.installHandler(LoadedMekanismBridge.heatHandler());

        RequirementPlan result = heatHandler().plan(
                LoadedHeatRequirement.minimumTemperature(350D), List.of(new FakeHeatPort(360D, IOType.OUTPUT)),
                testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().reason()).isSameAs(MekanismFailureReasons.HEAT_TEMPERATURE_INSUFFICIENT);
    }

    @Test
    void heat_output_without_a_port_is_blocked() {
        LoadedHeatRequirement.installHandler(LoadedMekanismBridge.heatHandler());

        RequirementPlan result = heatHandler().plan(
                LoadedHeatRequirement.outputHeat(5D), List.of(), testContext());

        assertThat(result.successful()).isFalse();
        assertThat(result.failure().reason()).isSameAs(MekanismFailureReasons.HEAT_OUTPUT_BLOCKED);
    }

    @Test
    void heat_output_uses_transactional_handle_heat() {
        LoadedHeatRequirement.installHandler(LoadedMekanismBridge.heatHandler());
        FakeHeatPort port = new FakeHeatPort(360D, IOType.OUTPUT);

        RequirementPlan planned = heatHandler().plan(LoadedHeatRequirement.outputHeat(5D), List.of(port), testContext());
        RequirementPlan materialized = planned.materialize(1, new PlanningReservations(), null);

        assertThat(materialized.successful()).isTrue();
        try (Transaction transaction = Transaction.open(null)) {
            CapabilityResult result = materialized.operations().getFirst().commit(transaction);
            assertThat(result.success()).isTrue();
            transaction.commit();
        }
        assertThat(port.handledHeat()).isEqualTo(5D);
    }

    @SuppressWarnings("unchecked")
    private static RequirementHandler<LoadedChemicalRequirement> chemicalHandler() {
        RequirementType<LoadedChemicalRequirement> type = LoadedChemicalRequirement.TYPE;
        return (RequirementHandler<LoadedChemicalRequirement>) type.handler();
    }

    @SuppressWarnings("unchecked")
    private static RequirementHandler<LoadedHeatRequirement> heatHandler() {
        return (RequirementHandler<LoadedHeatRequirement>) LoadedHeatRequirement.TEMPERATURE_TYPE.handler();
    }

    private static cn.howxu.mmcr.api.capability.plan.PlanningContext testContext() {
        return new PlanningContext(1, 0);
    }

    private static Holder.Reference<Chemical> registerChemical(String path) {
        return registerChemical(path, false);
    }

    private static Holder.Reference<Chemical> registerChemical(String path, boolean radioactive) {
        ResourceKey<Chemical> key = ResourceKey.create(MekanismAPI.CHEMICAL_REGISTRY_NAME,
                Identifier.fromNamespaceAndPath("mmcr_test", path));
        MappedRegistry<Chemical> registry = (MappedRegistry<Chemical>) MekanismAPI.CHEMICAL_REGISTRY;
        return registry.get(key).orElseGet(() -> {
            registry.unfreeze(true);
            Chemical value = new Chemical(ChemicalBuilder.builder()) {
                @Override
                public boolean isRadioactive() {
                    return radioactive;
                }
            };
            Registry.register(registry, key.identifier(), value);
            registry.freeze();
            return registry.get(key).orElseThrow();
        });
    }

    private static final class FakeChemicalPort implements LoadedMekanismBridge.ChemicalPort {
        private final IChemicalTank tank;
        private final boolean radioactive;
        private final CapabilityView view;

        private FakeChemicalPort(IChemicalTank tank, cn.howxu.mmcr.util.IOType ioType) {
            this(tank, ioType, false);
        }

        private FakeChemicalPort(IChemicalTank tank, cn.howxu.mmcr.util.IOType ioType, boolean radioactive) {
            this.tank = tank;
            this.radioactive = radioactive;
            this.view = new CapabilityView() {
                @Override
                public CapabilityType type() {
                    return new CapabilityType(MekanismRecipeTypes.CHEMICAL);
                }

                @Override
                public CapabilityDirections directions() {
                    return CapabilityDirections.of(ioType);
                }
            };
        }

        @Override
        public IChemicalTank chemicalTank() {
            return tank;
        }

        @Override
        public boolean radioactive() {
            return radioactive;
        }

        @Override
        public CapabilityType type() {
            return view.type();
        }

        @Override
        public CapabilityView view() {
            return view;
        }
    }

    private static final class FakeChemicalTank implements IChemicalTank {
        private final long capacity;
        private final ChemicalAttributeValidator attributeValidator;
        private ChemicalResource resource = ChemicalResource.EMPTY;
        private long amount;

        private FakeChemicalTank(long capacity, ChemicalAttributeValidator attributeValidator) {
            this.capacity = capacity;
            this.attributeValidator = attributeValidator;
        }

        @Override
        public LargeResourceStack<ChemicalResource> asStack() {
            return new LargeResourceStack<>(resource, amount);
        }

        @Override
        public int insert(ChemicalResource resource, int amount, TransactionContext transaction,
                          AutomationType automationType) {
            long moved = Math.min(amount, Math.max(0L, capacity - this.amount));
            if (moved <= 0L || !isValid(resource)) return 0;
            setContents(resource, this.amount + moved, transaction);
            return (int) moved;
        }

        @Override
        public int extract(ChemicalResource resource, int amount, TransactionContext transaction,
                           AutomationType automationType) {
            if (!this.resource.equals(resource)) return 0;
            long moved = Math.min(amount, this.amount);
            setContents(this.resource, this.amount - moved, transaction);
            return (int) moved;
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
            return attributeValidator;
        }

        private long amount() {
            return amount;
        }
    }

    private static final class FakeHeatPort implements LoadedMekanismBridge.HeatPort {
        private final FakeHeatHandler handler;
        private final IOType ioType;
        private final CapabilityView view = new CapabilityView() {
            @Override
            public CapabilityType type() {
                return new CapabilityType(MekanismRecipeTypes.HEAT_TEMPERATURE);
            }

            @Override
            public CapabilityDirections directions() {
                return CapabilityDirections.of(ioType);
            }
        };

        private FakeHeatPort(double temperature) {
            this(temperature, IOType.INPUT);
        }

        private FakeHeatPort(double temperature, IOType ioType) {
            handler = new FakeHeatHandler(temperature);
            this.ioType = ioType;
        }

        @Override
        public IHeatHandler heatHandler() {
            return handler;
        }

        @Override
        public CapabilityType type() {
            return view.type();
        }

        @Override
        public CapabilityView view() {
            return view;
        }

        @Override
        public CapabilityOperation prepare(CapabilityRequest request) {
            throw new UnsupportedOperationException();
        }

        private double temperature() {
            return handler.getTemperature();
        }

        private double handledHeat() {
            return handler.handledHeat;
        }
    }

    private static final class FakeHeatHandler implements IHeatHandler {
        private final double temperature;
        private double handledHeat;

        private FakeHeatHandler(double temperature) {
            this.temperature = temperature;
        }

        @Override
        public double getTemperature() {
            return temperature;
        }

        @Override
        public double getInverseConduction() {
            return 1D;
        }

        @Override
        public double getHeatCapacity() {
            return 1D;
        }

        @Override
        public void handleHeat(double transfer, TransactionContext transaction) {
            handledHeat += transfer;
        }
    }
}
