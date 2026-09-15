package cn.howxu.mmcr.internal.runtime;

import cn.howxu.mmcr.MMCR;
import cn.howxu.mmcr.api.capability.CapabilitySnapshot;
import cn.howxu.mmcr.api.capability.MachineCapability;
import cn.howxu.mmcr.api.capability.tick.CapabilityTickContext;
import cn.howxu.mmcr.api.capability.tick.CapabilityTickPhase;
import cn.howxu.mmcr.api.capability.tick.CapabilityTickResult;
import cn.howxu.mmcr.api.capability.plan.CraftingPlan;
import cn.howxu.mmcr.api.capability.plan.CapabilityResult;
import cn.howxu.mmcr.api.capability.plan.OutputPolicy;
import cn.howxu.mmcr.api.capability.plan.PlanningResult;
import cn.howxu.mmcr.api.capability.status.BuiltinFailureReasons;
import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.capability.status.FailureOccurrence;
import cn.howxu.mmcr.api.capability.status.FailurePhase;
import cn.howxu.mmcr.api.capability.status.FailureReason;
import cn.howxu.mmcr.api.capability.status.StatusSeverity;
import cn.howxu.mmcr.api.machine.Machine;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.recipe.ActiveMachineRecipe;
import cn.howxu.mmcr.api.recipe.CraftingContext;
import cn.howxu.mmcr.api.recipe.IntegrationTypeHelper;
import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.helper.CraftingStatus;
import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;
import cn.howxu.mmcr.api.recipe.requirement.EnergyRequirement;
import cn.howxu.mmcr.api.recipe.requirement.FluidRequirement;
import cn.howxu.mmcr.api.recipe.requirement.ItemRequirement;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.publicapi.machine.MachineBehavior;
import cn.howxu.mmcr.api.publicapi.machine.MachineBehaviorContext;
import cn.howxu.mmcr.api.publicapi.machine.RecipeBehavior;
import cn.howxu.mmcr.api.publicapi.machine.RecipeFinishContext;
import cn.howxu.mmcr.api.publicapi.machine.RecipeStartContext;
import cn.howxu.mmcr.api.publicapi.machine.RecipeTickContext;
import cn.howxu.mmcr.api.publicapi.controller.ControllerScreenText;
import cn.howxu.mmcr.api.capability.facet.AsyncPlanningFacet;
import cn.howxu.mmcr.internal.recipe.AsyncRequirementPlanner;
import cn.howxu.mmcr.compat.mekanism.loaded.LoadedChemicalRequirement;
import cn.howxu.mmcr.compat.mekanism.MekanismRecipeTypes;
import cn.howxu.mmcr.internal.multiblock.StructureClaimRegistry;
import cn.howxu.mmcr.internal.registration.MachineRecipeConverter;
import cn.howxu.mmcr.internal.sync.FailureStatusCodec;
import cn.howxu.mmcr.internal.sync.FailureStatusMigration;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Owns one recipe lifecycle. Capability plans are the only mutable-resource boundary.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class CraftingRuntime {
    private final MachineControllerBlockEntity controller;
    private final ComponentRuntime components;
    private @Nullable ControllerScreenText screenText;
    private @Nullable ActiveMachineRecipe activeRecipe;
    private @Nullable CraftingPlan startPlan;
    private @Nullable CraftingPlan finishPlan;
    private List<MachineRequirement> effectiveRequirements = List.of();
    private List<MachineOutput> effectiveOutputs = List.of();
    private Set<Integer> consumedAtStart = Set.of();
    private Set<Integer> retainedInputs = Set.of();
    private @Nullable ExecutionStatus failure;
    private long structureVersion = Long.MIN_VALUE;
    private long capabilityVersion = Long.MIN_VALUE;
    private long modifierVersion = Long.MIN_VALUE;
    private long componentStateVersion = Long.MIN_VALUE;
    private long upgradeContentRevision = Long.MIN_VALUE;
    private @Nullable StructureClaimRegistry.ResourceDomain resourceDomain;
    private CraftingStatus status = CraftingStatus.IDLE;
    private boolean finishCommitInProgress;
    private boolean patternStartReserved;
    private boolean smartInterfaceChangePending;
    private @Nullable ExecutionStatus capabilityTickFailure;
    private @Nullable AsyncTickPreparation asyncTickPreparation;
    private @Nullable RecipeFinishContext preparedAsyncFinishContext;

    public CraftingRuntime(MachineControllerBlockEntity controller, ComponentRuntime components) {
        if (controller == null) throw new IllegalArgumentException("controller must not be null");
        if (components == null) throw new IllegalArgumentException("components must not be null");
        this.controller = controller;
        this.components = components;
    }

    public void setScreenText(@Nullable ControllerScreenText screenText) {
        this.screenText = screenText;
    }

    /** Prepares a pattern start without consuming resources or occupying this runtime. */
    public @Nullable PreparedStart preparePatternStart(MachineRecipe recipe, long requestedParallelism,
                                                       List<MachineCapability> requestCapabilities) {
        if (recipe == null || requestedParallelism <= 0 || active() || patternStartReserved) return null;
        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        if (!recipeBelongsToMachine(recipe, runtime)
                || !runtime.moduleConnectionStatus().canRunRecipe(recipe.requiredHostIds())) return null;
        RecipeBehavior behavior = recipeBehavior(runtime);
        if (behavior == null) return null;
        long effectiveParallelism = Math.max(1L, Math.min(requestedParallelism, runtime.maxParallelism()));
        List<RecipeModifier> contextModifiers = contextModifiers(runtime);
        List<MachineRequirement> requirements = recipe.runtimeRequirements(contextModifiers);
        List<MachineOutput> outputs = runtimeMachineOutputs(recipe, runtime);
        MachineBehaviorContext machineContext = behaviorContext();
        RecipeStartContext startContext = new RecipeStartContext(machineContext, recipe, requestedParallelism,
                effectiveParallelism, duration(recipe, runtime),
                MachineRecipeConverter.toPublicRequirements(requirements), outputs);
        try {
            behavior.beforeStart().accept(startContext);
        } catch (RuntimeException exception) {
            logCallbackFailure("beforeStart", runtime, recipe, exception);
            return null;
        } finally {
            flushScreenTextReplacements(machineContext.screenText());
        }
        if (startContext.cancelled()) return null;
        RecipeStartContext.ExecutionSnapshot effective = startContext.snapshot();
        PlanningResult result = context(runtime, requestCapabilities).planInputs(effective.requirements().stream()
                        .map(MachineRecipeConverter::toRequirement).toList(), requestedParallelism,
                Set.of(), Set.of());
        CraftingPlan plan = result.plan();
        if (!result.successful() || plan == null) return null;
        return new PreparedStart(recipe, runtime, effective, plan);
    }

    public boolean reservePatternStart() {
        if (active() || patternStartReserved) return false;
        patternStartReserved = true;
        return true;
    }

    public void releasePatternStart() {
        patternStartReserved = false;
    }

    public boolean commitPatternStart(PreparedStart prepared) {
        return commitPatternStart(prepared, ignored -> { });
    }

    /** Commits a prepared pattern start with related storage writes in the same input transaction. */
    public boolean commitPatternStart(PreparedStart prepared, Consumer<TransactionContext> transactionWrites) {
        if (!patternStartReserved || active() || prepared == null) return false;
        if (!prepared.plan().commit(transactionWrites)) return false;
        activeRecipe = new ActiveMachineRecipe(prepared.recipe(), prepared.plan().parallelism(), prepared.effective());
        activeRecipe.setParallelism(prepared.plan().parallelism());
        startPlan = prepared.plan();
        finishPlan = null;
        effectiveRequirements = MachineRequirement.copyList(prepared.effective().requirements().stream()
                .map(MachineRecipeConverter::toRequirement).toList());
        effectiveOutputs = MachineOutput.copyList(prepared.effective().outputs());
        captureInputState(effectiveRequirements, prepared.plan());
        captureVersions(prepared.runtime());
        patternStartReserved = false;
        status = CraftingStatus.working();
        failure = null;
        controller.onPatternStartCommitted();
        return true;
    }

    public record PreparedStart(MachineRecipe recipe, ControllerRuntimeSnapshot runtime,
                                RecipeStartContext.ExecutionSnapshot effective, CraftingPlan plan) {
    }

    public CraftingStatus start(MachineRecipe recipe, long requestedParallelism) {
        return start(recipe, requestedParallelism, null);
    }

    /** Executes the start behavior callback before a shared-IO transaction is requested. */
    public @Nullable RecipeStartContext.ExecutionSnapshot prepareAsyncStart(MachineRecipe recipe, long requestedParallelism) {
        if (recipe == null || requestedParallelism <= 0 || active() || patternStartReserved) return null;
        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        if (!recipeBelongsToMachine(recipe, runtime)
                || !runtime.moduleConnectionStatus().canRunRecipe(recipe.requiredHostIds())) return null;
        RecipeBehavior behavior = recipeBehavior(runtime);
        if (behavior == null) return null;
        long effectiveParallelism = Math.max(1L, Math.min(requestedParallelism, runtime.maxParallelism()));
        List<MachineRequirement> requirements = recipe.runtimeRequirements(contextModifiers(runtime));
        MachineBehaviorContext machineContext = behaviorContext();
        RecipeStartContext startContext = new RecipeStartContext(machineContext, recipe, requestedParallelism,
                effectiveParallelism, duration(recipe, runtime),
                MachineRecipeConverter.toPublicRequirements(requirements), runtimeMachineOutputs(recipe, runtime));
        try {
            behavior.beforeStart().accept(startContext);
        } catch (RuntimeException exception) {
            logCallbackFailure("beforeStart", runtime, recipe, exception);
            fail(failure(BuiltinFailureReasons.RECIPE_BEHAVIOR, FailurePhase.RECIPE_START, Map.of()));
            return null;
        }
        if (startContext.cancelled()) {
            failure = null;
            status = CraftingStatus.IDLE;
            return null;
        }
        return startContext.snapshot();
    }

    public CraftingStatus start(MachineRecipe recipe, long requestedParallelism,
                                @Nullable RecipeStartContext.ExecutionSnapshot preparedStart) {
        if (recipe == null || requestedParallelism <= 0) {
            return fail(failure(BuiltinFailureReasons.RECIPE_START, FailurePhase.RECIPE_START, Map.of()));
        }
        if (active() || patternStartReserved) return status;

        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        if (!recipeBelongsToMachine(recipe, runtime)) {
            return fail(failure(BuiltinFailureReasons.RECIPE_START, FailurePhase.RECIPE_START,
                    Map.of("reason", "recipe_pool")));
        }
        if (!runtime.moduleConnectionStatus().canRunRecipe(recipe.requiredHostIds())) {
            return fail(failure(BuiltinFailureReasons.MODULE_CONNECTION, FailurePhase.RECIPE_START, Map.of()));
        }
        RecipeBehavior behavior = recipeBehavior(runtime);
        if (behavior == null) {
            return fail(failure(BuiltinFailureReasons.RECIPE_BEHAVIOR, FailurePhase.RECIPE_START, Map.of()));
        }
        RecipeStartContext.ExecutionSnapshot effective = preparedStart == null
                ? prepareAsyncStart(recipe, requestedParallelism) : preparedStart;
        if (effective == null) return status;
        CraftingContext context = context(runtime);
        PlanningResult result = context.planInputs(effective.requirements().stream()
                        .map(MachineRecipeConverter::toRequirement).toList(), requestedParallelism,
                Set.of(), Set.of());
        CraftingPlan plan = result.plan();
        if (!result.successful() || plan == null) {
            return fail(result.failure());
        }
        if (!plan.commitInputs()) {
            return fail(plan.failure());
        }

        activeRecipe = new ActiveMachineRecipe(recipe, plan.parallelism(), effective);
        activeRecipe.setParallelism(plan.parallelism());
        startPlan = plan;
        finishPlan = null;
        effectiveRequirements = MachineRequirement.copyList(effective.requirements().stream()
                .map(MachineRecipeConverter::toRequirement).toList());
        effectiveOutputs = MachineOutput.copyList(effective.outputs());
        captureInputState(effectiveRequirements, plan);
        captureVersions(runtime);
        status = CraftingStatus.working();
        failure = null;
        return status;
    }

    public CraftingStatus tick() {
        if (!active()) return status;
        if (!versionsCurrent()) return invalidate(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.RUNTIME);
        if (activeRecipe.isFinishPending()) return status;

        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        RecipeBehavior behavior = recipeBehavior(runtime);
        if (behavior == null) {
            return waiting(failure(BuiltinFailureReasons.RECIPE_BEHAVIOR, FailurePhase.PER_TICK, Map.of()));
        }
        MachineBehaviorContext machineContext = behaviorContext();
        RecipeTickContext recipeTickContext = new RecipeTickContext(machineContext, activeRecipe.getRecipe(),
                activeRecipe.getTick(), activeRecipe.getTotalTick(), activeRecipe.getParallelism(),
                MachineRecipeConverter
                        .toPublicRequirements(effectiveRequirements()), activeOutputs(),
                new CapabilitySnapshot(components.capabilities()));
        if (!executeTickPhase(CapabilityTickPhase.BEFORE_RECIPE, machineContext, recipeTickContext)) return status;
        try {
            behavior.recipeTick().accept(recipeTickContext);
        } catch (RuntimeException exception) {
            logCallbackFailure("recipeTick", runtime, activeRecipe.getRecipe(), exception);
        } finally {
            flushScreenTextReplacements(machineContext.screenText());
        }
        CraftingContext context = context(runtime);
        PlanningResult result = planPerTick(context);
        CraftingPlan tickPlan = result.plan();
        if (!result.successful() || tickPlan == null || tickPlan.parallelism() < activeRecipe.getParallelism()) {
            return waiting(result.failure());
        }
        try {
            if (!tickPlan.commit()) return waiting(tickPlan.failure());
        } catch (RuntimeException exception) {
            logTickFailure("commit", runtime, activeRecipe.getRecipe(), exception);
            return waiting(failure(BuiltinFailureReasons.PER_TICK, FailurePhase.PER_TICK, Map.of()));
        }
        if (!executeTickPhase(CapabilityTickPhase.AFTER_INPUTS, machineContext, recipeTickContext)) {
            if (activeRecipe != null) activeRecipe.applyTickGrant(true, false, currentGameTime());
            return status;
        }

        int gameTime = currentGameTime();
        if (activeRecipe.needsFinishCommit()) {
            if (!executeTickPhase(CapabilityTickPhase.AFTER_RECIPE, machineContext, recipeTickContext)) {
                if (activeRecipe != null) activeRecipe.applyTickGrant(true, false, gameTime);
                return status;
            }
            activeRecipe.beginFinishCommit();
            status = CraftingStatus.working();
            return status;
        }
        activeRecipe.applyTickGrant(true, false, gameTime);
        if (!executeTickPhase(CapabilityTickPhase.AFTER_RECIPE, machineContext, recipeTickContext)) return status;
        status = CraftingStatus.working();
        failure = null;
        return status;
    }

    /** Captures the main-thread recipe tick context before any worker-side planning begins. */
    public boolean prepareAsyncTick() {
        asyncTickPreparation = null;
        if (!active()) return false;
        if (!versionsCurrent()) {
            invalidate(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.RUNTIME);
            return false;
        }
        if (activeRecipe.isFinishPending()) return false;
        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        RecipeBehavior behavior = recipeBehavior(runtime);
        if (behavior == null) {
            waiting(failure(BuiltinFailureReasons.RECIPE_BEHAVIOR, FailurePhase.PER_TICK, Map.of()));
            return false;
        }
        MachineBehaviorContext machineContext = behaviorContext();
        RecipeTickContext tickContext = new RecipeTickContext(machineContext, activeRecipe.getRecipe(),
                activeRecipe.getTick(), activeRecipe.getTotalTick(), activeRecipe.getParallelism(),
                MachineRecipeConverter.toPublicRequirements(effectiveRequirements()), activeOutputs(),
                new CapabilitySnapshot(components.capabilities()));
        asyncTickPreparation = new AsyncTickPreparation(runtime, machineContext, tickContext);
        return true;
    }

    /** Executes one deferred capability tick phase against the captured recipe tick context. */
    public boolean executeAsyncCapabilityTick(CapabilityTickPhase phase) {
        AsyncTickPreparation preparation = asyncTickPreparation;
        return preparation != null && executeAsyncTickPhase(phase, preparation.machineContext(), preparation.tickContext());
    }

    public void discardAsyncTickPreparation() {
        asyncTickPreparation = null;
    }

    /** Produces the worker-safe native requirement plan after main-thread tick callbacks have completed. */
    public @Nullable AsyncRequirementPlanner.PreparedPlan prepareAsyncTickPlan() {
        AsyncTickPreparation preparation = asyncTickPreparation;
        if (preparation == null) return null;
        RecipeBehavior behavior = recipeBehavior(preparation.runtime());
        if (behavior == null) return null;
        try {
            behavior.recipeTick().accept(preparation.tickContext());
        } catch (RuntimeException exception) {
            logCallbackFailure("recipeTick", preparation.runtime(), activeRecipe.getRecipe(), exception);
        }
        return context(preparation.runtime()).planAsync(perTickRequirements(), activeRecipe.getParallelism());
    }

    /** Flushes recipe behavior screen text after its callback has run on the server thread. */
    public void flushAsyncScreenText() {
        AsyncTickPreparation preparation = asyncTickPreparation;
        flushScreenTextReplacements(preparation == null ? behaviorContext().screenText() : preparation.machineContext().screenText());
    }

    /** Commits worker-planned native operations inside the shared-IO arbitration transaction. */
    public boolean commitAsyncTick(AsyncRequirementPlanner.PlanResult planned) {
        AsyncTickPreparation preparation = asyncTickPreparation;
        if (preparation == null || !active()) return false;
        if (!versionsCurrent()) {
            invalidate(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.RUNTIME);
            return false;
        }
        return commitAsyncTickPlan(planned, preparation.runtime());
    }

    /** Runs the AFTER_INPUTS capability phase after the shared-IO transaction has committed. */
    public boolean completeAsyncTickAfterInputs() {
        AsyncTickPreparation preparation = asyncTickPreparation;
        if (preparation == null || !active()) return false;
        if (!versionsCurrent()) {
            invalidate(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.RUNTIME);
            return false;
        }
        if (!executeAsyncTickPhase(CapabilityTickPhase.AFTER_INPUTS, behaviorContext(), preparation.tickContext())) {
            if (activeRecipe != null) activeRecipe.applyTickGrant(true, false, currentGameTime());
            asyncTickPreparation = null;
            return false;
        }
        return true;
    }

    /** Runs the AFTER_RECIPE capability phase and publishes tick progress after the previous explicit steps succeed. */
    public CraftingStatus completeAsyncTickAfterRecipe() {
        AsyncTickPreparation preparation = asyncTickPreparation;
        asyncTickPreparation = null;
        if (preparation == null || !active()) return status;
        if (!versionsCurrent()) return invalidate(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.RUNTIME);
        int gameTime = currentGameTime();
        if (activeRecipe.needsFinishCommit()) {
            if (!executeAsyncTickPhase(CapabilityTickPhase.AFTER_RECIPE, behaviorContext(), preparation.tickContext())) {
                if (activeRecipe != null) activeRecipe.applyTickGrant(true, false, gameTime);
                return status;
            }
            activeRecipe.beginFinishCommit();
            status = CraftingStatus.working();
            return status;
        }
        activeRecipe.applyTickGrant(true, false, gameTime);
        if (!executeAsyncTickPhase(CapabilityTickPhase.AFTER_RECIPE, behaviorContext(), preparation.tickContext())) return status;
        status = CraftingStatus.working();
        failure = null;
        return status;
    }

    public CapabilityTickResult tickIdle() {
        MachineBehaviorContext machineContext = behaviorContext();
        return handleCapabilityTickResult(components.executeTickPhase(new CapabilityTickContext(capabilityGameTime(), CapabilityTickPhase.IDLE,
                null, 1L, new CapabilitySnapshot(components.capabilities()), machineContext)));
    }

    public CapabilityTickResult handleCapabilityTickResult(CapabilityTickResult result) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        if (result.failure() != null) {
            capabilityTickFailure = result.failure();
            waiting(result.failure());
        } else if (capabilityTickFailure != null && capabilityTickFailure.equals(failure)) {
            capabilityTickFailure = null;
            failure = null;
            status = active() ? CraftingStatus.working() : CraftingStatus.IDLE;
        }
        if (result.failure() != null || result.stateChanged()) controller.syncRecipeRuntimeFailure(this);
        if (result.stateChanged()) controller.setChanged();
        return result;
    }

    public CraftingStatus finish() {
        if (!active()) return status;
        if (!versionsCurrent()) return invalidate(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.RUNTIME);
        if (!activeRecipe.isFinishPending()) return status;
        if (!activeRecipe.shouldRetryFinish(currentGameTime())) return status;

        RecipeFinishContext finishContext = preparedAsyncFinishContext;
        preparedAsyncFinishContext = null;
        if (finishContext == null) {
            finishContext = prepareFinishContext();
            if (finishContext == null) return status;
        }
        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        CraftingContext context = context(runtime);
        if (finishContext.cancelled()) {
            return finishBlocked(failure(BuiltinFailureReasons.BEHAVIOR_BEFORE_FINISH_CANCELLED,
                    FailurePhase.FINISH, Map.of()));
        }
        if (finishContext.outputsDiscarded()) {
            activeRecipe.applyTickGrant(true, true, currentGameTime());
            activeRecipe = null;
            startPlan = null;
            finishPlan = null;
            clearEffectiveRecipe();
            consumedAtStart = Set.of();
            retainedInputs = Set.of();
            resourceDomain = null;
            failure = null;
            status = CraftingStatus.IDLE;
            return status;
        }
        PlanningResult result;
        try {
            result = context.planOutputRequirements(finishRequirements(), finishOutputs(finishContext),
                    activeRecipe.getParallelism(), activeRecipe.getRecipe().allowPartialOutputs());
        } catch (IllegalArgumentException exception) {
            logCallbackFailure("beforeFinish.output_validation", runtime, activeRecipe.getRecipe(), exception);
            return finishBlocked(failure(BuiltinFailureReasons.INVALID_OUTPUTS, FailurePhase.FINISH, Map.of()));
        } catch (RuntimeException exception) {
            logFinishFailure("planning", runtime, activeRecipe.getRecipe(), exception);
            return finishBlocked(failure(BuiltinFailureReasons.FINISH, FailurePhase.FINISH, Map.of()));
        }
        finishPlan = result.plan();
        if (!result.successful() || finishPlan == null) {
            activeRecipe.markFinishBlocked(currentGameTime());
            return finishBlocked(result.failure());
        }
        finishCommitInProgress = true;
        boolean committed;
        try {
            committed = finishPlan.commit();
        } catch (RuntimeException exception) {
            logFinishFailure("commit", runtime, activeRecipe.getRecipe(), exception);
            return finishBlocked(failure(BuiltinFailureReasons.FINISH, FailurePhase.FINISH, Map.of()));
        } finally {
            finishCommitInProgress = false;
        }
        if (!committed) {
            activeRecipe.markFinishBlocked(currentGameTime());
            return finishBlocked(finishPlan.failure());
        }
        if (smartInterfaceChangePending) {
            smartInterfaceChangePending = false;
            return invalidate(BuiltinFailureReasons.SMART_INTERFACE_CHANGED, FailurePhase.RUNTIME);
        }

        activeRecipe.applyTickGrant(true, true, currentGameTime());
        activeRecipe = null;
        startPlan = null;
        finishPlan = null;
        preparedAsyncFinishContext = null;
        clearEffectiveRecipe();
        consumedAtStart = Set.of();
        retainedInputs = Set.of();
        resourceDomain = null;
        failure = null;
        status = CraftingStatus.IDLE;
        return status;
    }

    /** Executes the finish behavior callback before a shared-IO output transaction is requested. */
    public boolean prepareAsyncFinish() {
        if (!active() || !versionsCurrent() || !activeRecipe.isFinishPending()
                || !activeRecipe.shouldRetryFinish(currentGameTime())) return false;
        preparedAsyncFinishContext = prepareFinishContext();
        return preparedAsyncFinishContext != null;
    }

    private @Nullable RecipeFinishContext prepareFinishContext() {
        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        RecipeBehavior behavior = recipeBehavior(runtime);
        if (behavior == null) {
            finishBlocked(failure(BuiltinFailureReasons.RECIPE_BEHAVIOR, FailurePhase.FINISH, Map.of()));
            return null;
        }
        MachineBehaviorContext machineContext = behaviorContext();
        RecipeFinishContext finishContext = new RecipeFinishContext(machineContext,
                activeRecipe.getRecipe(), activeRecipe.getMaxParallelism(), activeRecipe.getParallelism(), activeOutputs());
        try {
            behavior.beforeFinish().accept(finishContext);
        } catch (RuntimeException exception) {
            logCallbackFailure("beforeFinish", runtime, activeRecipe.getRecipe(), exception);
            finishBlocked(failure(BuiltinFailureReasons.BEHAVIOR_BEFORE_FINISH, FailurePhase.FINISH, Map.of()));
            return null;
        }
        if (finishContext.cancelled()) {
            finishBlocked(failure(BuiltinFailureReasons.BEHAVIOR_BEFORE_FINISH_CANCELLED, FailurePhase.FINISH, Map.of()));
            return null;
        }
        return finishContext;
    }

    public boolean active() {
        return activeRecipe != null;
    }

    private boolean executeTickPhase(CapabilityTickPhase phase, MachineBehaviorContext machineContext,
                                     RecipeTickContext recipeTickContext) {
        CapabilityTickResult result;
        try {
            result = handleCapabilityTickResult(components.executeTickPhase(new CapabilityTickContext(
                    capabilityGameTime(), phase,
                    recipeTickContext, activeRecipe.getParallelism(), new CapabilitySnapshot(components.capabilities()),
                    machineContext)));
        } catch (RuntimeException exception) {
            MMCR.LOG.warn("Machine recipe tick capability phase failed: phase={} controller={}", phase,
                    controller.getBlockPos(), exception);
            handleCapabilityTickResult(new CapabilityTickResult(List.of(),
                    failure(BuiltinFailureReasons.PER_TICK, FailurePhase.PER_TICK, Map.of()), false));
            return false;
        }
        if (result.failure() == null) return true;
        return false;
    }

    private boolean executeAsyncTickPhase(CapabilityTickPhase phase, MachineBehaviorContext machineContext,
                                          RecipeTickContext recipeTickContext) {
        return executeTickPhase(phase, machineContext, recipeTickContext);
    }

    public void pause() {
        if (active()) status = CraftingStatus.paused();
    }

    public void resume() {
        if (active()) status = CraftingStatus.working();
    }

    public @Nullable ExecutionStatus failure() {
        return failure;
    }

    public CraftingStateSnapshot snapshot() {
        ActiveMachineRecipe recipe = activeRecipe;
        String lockedRecipeId = controller.lockedRecipeId() == null ? "" : controller.lockedRecipeId().toString();
        return new CraftingStateSnapshot(activeRecipe == null ? null : activeRecipe.getRecipe().id(), status, failure,
                structureVersion == Long.MIN_VALUE ? 0L : structureVersion,
                capabilityVersion == Long.MIN_VALUE ? 0L : capabilityVersion,
                modifierVersion == Long.MIN_VALUE ? 0L : modifierVersion,
                tickCount(), totalTick(), parallelism(), recipe == null ? 1 : recipe.getMaxParallelism(),
                !lockedRecipeId.isEmpty(), lockedRecipeId);
    }

    public @Nullable MachineRecipe recipe() {
        return activeRecipe == null ? null : activeRecipe.getRecipe();
    }

    public @Nullable ActiveMachineRecipe activeRecipe() {
        return activeRecipe;
    }

    /**
     * Temporary string presentation boundary for unchanged Task 7 packet/menu callers.
     * Remove it when those callers consume the typed failure directly.
     */
    public @Nullable String failureUnloc() {
        return failure == null ? null : failureUnloc(failure);
    }

    public int tickCount() {
        return activeRecipe == null ? 0 : activeRecipe.getTick();
    }

    public int totalTick() {
        return activeRecipe == null ? 0 : activeRecipe.getTotalTick();
    }

    public long parallelism() {
        return activeRecipe == null ? 0 : activeRecipe.getParallelism();
    }

    public long maxParallelism() {
        return activeRecipe == null ? 1L : activeRecipe.getMaxParallelism();
    }

    public boolean finishPending() {
        return activeRecipe != null && activeRecipe.isFinishPending();
    }

    public boolean shouldRetryFinish() {
        return activeRecipe != null && activeRecipe.shouldRetryFinish(currentGameTime());
    }

    public void recordSearchFailure(@Nullable ExecutionStatus nextFailure) {
        failure = nextFailure == null
                ? failure(BuiltinFailureReasons.RECIPE_SEARCH, FailurePhase.RECIPE_SEARCH, Map.of()) : nextFailure;
        status = CraftingStatus.failure(failureUnloc(failure));
    }

    public boolean versionsCurrent() {
        if (!active()) return true;
        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        return structureVersion == runtime.structure().version()
                && capabilityVersion == runtime.capabilityVersion()
                && modifierVersion == runtime.modifierVersion()
                && componentStateVersion == runtime.stateVersion()
                && upgradeContentRevision == runtime.upgradeContentRevision()
                && recipeBelongsToMachine(activeRecipe.getRecipe(), runtime);
    }

    public @Nullable StructureClaimRegistry.ResourceDomain resourceDomain() {
        return resourceDomain;
    }

    public void invalidate() {
        activeRecipe = null;
        startPlan = null;
        finishPlan = null;
        preparedAsyncFinishContext = null;
        clearEffectiveRecipe();
        consumedAtStart = Set.of();
        retainedInputs = Set.of();
        resourceDomain = null;
        smartInterfaceChangePending = false;
        patternStartReserved = false;
        failure = null;
        status = CraftingStatus.IDLE;
    }

    public void invalidateForSmartInterfaceChange() {
        if (!active()) return;
        if (finishCommitInProgress) {
            smartInterfaceChangePending = true;
            return;
        }
        invalidate(BuiltinFailureReasons.SMART_INTERFACE_CHANGED, FailurePhase.RUNTIME);
    }

    public void invalidateForCatalogChange() {
        if (active()) invalidate(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.RUNTIME);
    }

    public void restore(ActiveMachineRecipe restored, @Nullable StructureClaimRegistry.ResourceDomain domain,
                        long restoredStructureVersion, long restoredCapabilityVersion,
                        long restoredModifierVersion, long restoredComponentStateVersion) {
        if (restored == null || restored.getRecipe() == null) {
            failLoad();
            return;
        }
        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        if (!recipeBelongsToMachine(restored.getRecipe(), runtime)) {
            failLoad();
            return;
        }
        List<MachineRequirement> requirements = restored.hasEffectiveExecutionSnapshot()
                ? restored.effectiveRequirements()
                : restored.getRecipe().runtimeRequirements(contextModifiers(runtime));
        if (!restored.hasValidInputConsumptionPlan(requirements)) {
            failLoad();
            return;
        }
        List<MachineOutput> outputs = restored.hasEffectiveExecutionSnapshot()
                ? restored.effectiveOutputs()
                : restored.getRecipe().runtimeMachineOutputs(contextModifiers(runtime));
        if (!restored.hasEffectiveExecutionSnapshot()) {
            restored.setEffectiveExecutionSnapshot(new RecipeStartContext.ExecutionSnapshot(
                    duration(restored.getRecipe(), runtime),
                    MachineRecipeConverter.toPublicRequirements(requirements), outputs));
            if (restored.getTotalTick() < 1 || restored.getTick() < 0
                    || restored.getTick() > restored.getTotalTick()
                    || (restored.isFinishPending() && restored.getTick() != restored.getTotalTick() - 1)) {
                failLoad();
                return;
            }
        }
        activeRecipe = restored;
        startPlan = null;
        finishPlan = null;
        resourceDomain = domain;
        structureVersion = restoredStructureVersion;
        capabilityVersion = restoredCapabilityVersion;
        modifierVersion = restoredModifierVersion;
        componentStateVersion = restoredComponentStateVersion;
        upgradeContentRevision = runtime.upgradeContentRevision();
        effectiveRequirements = MachineRequirement.copyList(requirements);
        effectiveOutputs = MachineOutput.copyList(outputs);
        Set<Integer> consumed = new HashSet<>();
        Set<Integer> retained = new HashSet<>();
        for (int index = 0; index < requirements.size(); index++) {
            MachineRequirement requirement = requirements.get(index);
            if (!(ItemRequirement.TYPE.equals(requirement.type()) || FluidRequirement.TYPE.equals(requirement.type())
                    || LoadedChemicalRequirement.TYPE.equals(requirement.type()))
                    || requirement.io() != RecipeModifier.IOType.INPUT) continue;
            if (restored.inputConsumptionPlan().consumedBatches(index) > 0) consumed.add(index);
            else retained.add(index);
        }
        consumedAtStart = Set.copyOf(consumed);
        retainedInputs = Set.copyOf(retained);
        List<Integer> consumedBatches = new ArrayList<>(requirements.size());
        for (int index = 0; index < requirements.size(); index++) {
            consumedBatches.add(consumed.contains(index) ? 1 : 0);
        }
        activeRecipe.setInputConsumptionPlan(new ActiveMachineRecipe.InputConsumptionPlan(consumedBatches));
        status = CraftingStatus.working();
        failure = null;
    }

    public void rebindCurrentVersions() {
        if (!active()) return;
        ControllerRuntimeSnapshot runtime = controller.currentRuntimeSnapshot();
        if (!recipeBelongsToMachine(activeRecipe.getRecipe(), runtime)) {
            invalidate(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.RUNTIME);
            return;
        }
        captureVersions(runtime);
    }

    public void save(ValueOutput output) {
        boolean present = activeRecipe != null && activeRecipe.getRecipe() != null;
        output.putBoolean("active", present);
        FailureStatusCodec.write(output.child("failure"), failure);
        if (present) {
            output.putLong("structure_version", structureVersion);
            output.putLong("capability_version", capabilityVersion);
            output.putLong("modifier_version", modifierVersion);
            output.putLong("component_state_version", componentStateVersion);
            output.putLong("upgrade_content_revision", upgradeContentRevision);
            activeRecipe.serialize(output.child("recipe"), registryAccess());
        }
    }

    public void load(ValueInput input, @Nullable StructureClaimRegistry.ResourceDomain domain) {
        boolean active = input.getBooleanOr("active", false);
        if (!active) {
            invalidate();
            restoreFailure(readFailure(input, null));
            return;
        }
        Identifier recipePoolId = recipePoolId(controller.currentRuntimeSnapshot());
        if (recipePoolId == null) {
            failLoad();
            return;
        }
        ActiveMachineRecipe.LoadResult loaded = ActiveMachineRecipe.loadForPool(
                input.childOrEmpty("recipe"), recipePoolId);
        if (!loaded.successful()) {
            failLoad();
            return;
        }
        long restoredUpgradeContentRevision = input.getLongOr("upgrade_content_revision", Long.MIN_VALUE);
        restore(loaded.recipe(), domain,
                input.getLongOr("structure_version", Long.MIN_VALUE),
                input.getLongOr("capability_version", Long.MIN_VALUE),
                input.getLongOr("modifier_version", Long.MIN_VALUE),
                input.getLongOr("component_state_version", Long.MIN_VALUE));
        if (active()) {
            if (restoredUpgradeContentRevision != Long.MIN_VALUE) {
                upgradeContentRevision = restoredUpgradeContentRevision;
            }
            restoreFailure(readFailure(input, loaded.recipe().getRecipe().id()));
        }
    }

    private static @Nullable ExecutionStatus readFailure(ValueInput input, @Nullable Identifier recipeId) {
        var failureInput = input.child("failure");
        if (failureInput.isPresent()) return FailureStatusCodec.read(failureInput.get());
        if (!input.getBooleanOr("has_failure", false)) return null;
        return FailureStatusMigration.craftingFailure(input.getStringOr("failure_reason", ""), recipeId);
    }

    private void failLoad() {
        invalidate();
        failure = failure(BuiltinFailureReasons.RECIPE_LOAD, FailurePhase.RECIPE_LOAD, Map.of());
        status = CraftingStatus.failure(failureUnloc(failure));
    }

    private void restoreFailure(@Nullable ExecutionStatus restoredFailure) {
        if (restoredFailure == null) return;
        failure = restoredFailure;
        status = CraftingStatus.failure(failureUnloc(failure));
    }

    private CraftingStatus waiting(@Nullable ExecutionStatus nextFailure) {
        failure = nextFailure == null
                ? failure(BuiltinFailureReasons.PER_TICK, FailurePhase.PER_TICK, Map.of()) : nextFailure;
        status = CraftingStatus.failure(failureUnloc(failure));
        if (activeRecipe != null && activeRecipe.getRecipe().doesCancelRecipeOnPerTickFailure()) {
            activeRecipe = null;
            startPlan = null;
            finishPlan = null;
            clearEffectiveRecipe();
            resourceDomain = null;
        }
        return status;
    }

    private CraftingStatus finishBlocked(@Nullable ExecutionStatus nextFailure) {
        if (activeRecipe != null) activeRecipe.markFinishBlocked(currentGameTime());
        failure = nextFailure == null
                ? failure(BuiltinFailureReasons.FINISH, FailurePhase.FINISH, Map.of()) : nextFailure;
        status = CraftingStatus.failure(failureUnloc(failure));
        return status;
    }

    private CraftingStatus invalidate(FailureReason reason, FailurePhase phase) {
        failure = failure(reason, phase, Map.of());
        activeRecipe = null;
        startPlan = null;
        finishPlan = null;
        preparedAsyncFinishContext = null;
        clearEffectiveRecipe();
        consumedAtStart = Set.of();
        retainedInputs = Set.of();
        resourceDomain = null;
        status = CraftingStatus.failure(failureUnloc(failure));
        return status;
    }

    private CraftingStatus fail(@Nullable ExecutionStatus nextFailure) {
        failure = nextFailure == null
                ? failure(BuiltinFailureReasons.RECIPE_START, FailurePhase.RECIPE_START, Map.of()) : nextFailure;
        status = CraftingStatus.failure(failureUnloc(failure));
        return status;
    }

    private MachineBehaviorContext behaviorContext() {
        return screenText == null ? controller.behaviorContext() : controller.behaviorContext(screenText);
    }

    private static void flushScreenTextReplacements(ControllerScreenText screenText) {
        if (screenText instanceof ControllerScreenTextState state) state.flushReplacements();
    }

    private CraftingContext context(ControllerRuntimeSnapshot runtime) {
        return new CraftingContext(new CapabilitySnapshot(components.capabilities()), contextModifiers(runtime));
    }

    private CraftingContext context(ControllerRuntimeSnapshot runtime, List<MachineCapability> requestCapabilities) {
        List<MachineCapability> capabilities = new ArrayList<>(components.capabilities());
        if (requestCapabilities != null) capabilities.addAll(requestCapabilities);
        return new CraftingContext(new CapabilitySnapshot(capabilities), contextModifiers(runtime));
    }

    private PlanningResult planPerTick(CraftingContext context) {
        List<MachineRequirement> requirements = perTickRequirements();
        Map<Integer, OutputPolicy> outputPolicies = new LinkedHashMap<>();
        for (int index = 0; index < requirements.size(); index++) {
            MachineRequirement requirement = requirements.get(index);
            if (isPerTickOutput(requirement)) {
                outputPolicies.put(index, activeRecipe.getRecipe().allowPartialOutputs()
                        ? OutputPolicy.ALLOW_PARTIAL : OutputPolicy.REQUIRE_FULL);
            }
        }
        return context.planRequirements(requirements, activeRecipe.getParallelism(), outputPolicies);
    }

    private boolean commitAsyncTickPlan(AsyncRequirementPlanner.PlanResult planned,
                                        ControllerRuntimeSnapshot runtime) {
        if (planned == null || !planned.mainThreadRequirements().isEmpty()) {
            PlanningResult fallback = planPerTick(context(runtime));
            CraftingPlan plan = fallback.plan();
            if (!fallback.successful() || plan == null || plan.parallelism() < activeRecipe.getParallelism()) {
                waiting(fallback.failure());
                return false;
            }
            try {
                if (!plan.commit()) {
                    waiting(plan.failure());
                    return false;
                }
                return true;
            } catch (RuntimeException exception) {
                logTickFailure("fallback_commit", runtime, activeRecipe.getRecipe(), exception);
                waiting(failure(BuiltinFailureReasons.PER_TICK, FailurePhase.PER_TICK, Map.of()));
                return false;
            }
        }
        List<AsyncPlanningFacet> facets = components.capabilities().stream()
                .map(capability -> capability.facet(AsyncPlanningFacet.class).orElse(null))
                .filter(Objects::nonNull).toList();
        try (Transaction transaction = Transaction.openRoot()) {
            for (AsyncRequirementPlanner.PlannedOperation operation : planned.operations()) {
                if (operation.capabilityIndex() >= facets.size()) {
                    waiting(failure(BuiltinFailureReasons.VERSION_INVALIDATED, FailurePhase.PER_TICK, Map.of()));
                    return false;
                }
                CapabilityResult result = facets.get(operation.capabilityIndex()).commit(operation.operation(), transaction);
                if (result == null || !result.success()) {
                    waiting(result == null ? failure(BuiltinFailureReasons.PER_TICK, FailurePhase.PER_TICK, Map.of())
                            : result.status());
                    return false;
                }
            }
            transaction.commit();
            return true;
        } catch (RuntimeException exception) {
            logTickFailure("async_commit", runtime, activeRecipe.getRecipe(), exception);
            waiting(failure(BuiltinFailureReasons.PER_TICK, FailurePhase.PER_TICK, Map.of()));
            return false;
        }
    }

    private List<MachineRequirement> perTickRequirements() {
        List<MachineRequirement> requirements = new ArrayList<>();
        List<MachineRequirement> source = effectiveRequirements();
        for (int index = 0; index < source.size(); index++) {
            MachineRequirement requirement = source.get(index);
            if (requirement.io() == RecipeModifier.IOType.INPUT) {
                if (consumedAtStart.contains(index)) continue;
                if (retainedInputs.contains(index) && requirement instanceof ItemRequirement item
                        && item.consumeChance() > 0F) {
                    requirement = new ItemRequirement(item.io(), item.item(), item.count(), item.stack(null),
                            item.chance(), item.tags(), item.components(), 0F);
                }
                requirements.add(requirement);
            } else if (isPerTickOutput(requirement)) {
                requirements.add(requirement);
            }
        }
        return requirements;
    }

    private List<MachineRequirement> finishRequirements() {
        return effectiveRequirements().stream()
                .filter(requirement -> !isPerTickOutput(requirement))
                .toList();
    }

    private List<MachineOutput> finishOutputs(RecipeFinishContext finishContext) {
        return finishContext.outputs().stream()
                .filter(output -> !isPerTickOutput(output))
                .toList();
    }

    private static boolean isPerTickOutput(MachineRequirement requirement) {
        return requirement.io() == RecipeModifier.IOType.OUTPUT
                && (requirement instanceof EnergyRequirement
                || MekanismRecipeTypes.HEAT.equals(requirement.type().id()));
    }

    private static boolean isPerTickOutput(MachineOutput output) {
        return MekanismRecipeTypes.HEAT.equals(output.outputType().id());
    }

    private RecipeBehavior recipeBehavior(ControllerRuntimeSnapshot runtime) {
        MachineBehavior behavior = runtime.structure().machine() == null
                ? runtime.structure().configuredMachine() == null ? null : runtime.structure().configuredMachine().behavior()
                : runtime.structure().machine().behavior();
        return behavior instanceof RecipeBehavior recipeBehavior ? recipeBehavior : null;
    }

    private static boolean recipeBelongsToMachine(MachineRecipe recipe, ControllerRuntimeSnapshot runtime) {
        Identifier recipePoolId = recipePoolId(runtime);
        return recipePoolId != null && recipePoolId.equals(recipe.recipePoolId());
    }

    private static @Nullable Identifier recipePoolId(ControllerRuntimeSnapshot runtime) {
        Machine machine = runtime.structure().machine() == null
                ? runtime.structure().configuredMachine() : runtime.structure().machine();
        return machine == null ? null : MachineRegistry.recipePoolForMachine(machine);
    }

    private void logCallbackFailure(String phase, ControllerRuntimeSnapshot runtime, MachineRecipe recipe,
                                    RuntimeException exception) {
        MMCR.LOG.warn("Machine behavior callback failed: phase={} machine={} recipe={} controller={}", phase,
                runtime.machineId(), recipe.id(), controller.getBlockPos(), exception);
    }

    private void logFinishFailure(String phase, ControllerRuntimeSnapshot runtime, MachineRecipe recipe,
                                   RuntimeException exception) {
        MMCR.LOG.warn("Machine recipe finish failed: phase={} machine={} recipe={} controller={}", phase,
                runtime.machineId(), recipe.id(), controller.getBlockPos(), exception);
    }

    private void logTickFailure(String phase, ControllerRuntimeSnapshot runtime, MachineRecipe recipe,
                                 RuntimeException exception) {
        MMCR.LOG.warn("Machine recipe tick failed: phase={} machine={} recipe={} controller={}", phase,
                runtime.machineId(), recipe.id(), controller.getBlockPos(), exception);
    }

    private List<RecipeModifier> contextModifiers(ControllerRuntimeSnapshot runtime) {
        return components.modifierList();
    }

    /** Returns outputs using the same runtime modifier context as pattern-start preparation. */
    public List<MachineOutput> runtimeMachineOutputs(MachineRecipe recipe, ControllerRuntimeSnapshot runtime) {
        return recipe.runtimeMachineOutputs(contextModifiers(runtime));
    }

    private @Nullable HolderLookup.Provider registryAccess() {
        return controller.getLevel() == null ? null : controller.getLevel().registryAccess();
    }

    private void captureVersions(ControllerRuntimeSnapshot runtime) {
        structureVersion = runtime.structure().version();
        capabilityVersion = runtime.capabilityVersion();
        modifierVersion = runtime.modifierVersion();
        componentStateVersion = runtime.stateVersion();
        upgradeContentRevision = runtime.upgradeContentRevision();
        resourceDomain = controller.resourceDomain();
    }

    private void captureInputState(List<MachineRequirement> requirements, CraftingPlan plan) {
        Set<Integer> consumed = new HashSet<>();
        Set<Integer> retained = new HashSet<>();
        for (int index = 0; index < requirements.size(); index++) {
            MachineRequirement requirement = requirements.get(index);
            if (!(ItemRequirement.TYPE.equals(requirement.type())
                    || FluidRequirement.TYPE.equals(requirement.type())
                    || LoadedChemicalRequirement.TYPE.equals(requirement.type()))
                    || requirement.io() != RecipeModifier.IOType.INPUT) continue;
            if (plan.hasOperations(index)) consumed.add(index);
            else retained.add(index);
        }
        consumedAtStart = Set.copyOf(consumed);
        retainedInputs = Set.copyOf(retained);
        List<Integer> consumedBatches = new ArrayList<>(requirements.size());
        for (int index = 0; index < requirements.size(); index++) {
            consumedBatches.add(consumed.contains(index) ? 1 : 0);
        }
        activeRecipe.setInputConsumptionPlan(new ActiveMachineRecipe.InputConsumptionPlan(consumedBatches));
    }

    private void clearEffectiveRecipe() {
        effectiveRequirements = List.of();
        effectiveOutputs = List.of();
    }

    private List<MachineRequirement> effectiveRequirements() {
        return activeRecipe != null && activeRecipe.hasEffectiveExecutionSnapshot()
                ? activeRecipe.effectiveRequirements() : effectiveRequirements;
    }

    public List<MachineOutput> activeOutputs() {
        List<MachineOutput> source = activeRecipe != null && activeRecipe.hasEffectiveExecutionSnapshot()
                ? activeRecipe.effectiveOutputs() : effectiveOutputs;
        return List.copyOf(source);
    }

    private int duration(MachineRecipe recipe, ControllerRuntimeSnapshot runtime) {
        List<RecipeModifier> modifiers = new ArrayList<>(recipe.modifiers());
        modifiers.addAll(contextModifiers(runtime));
        double levelMultiplier = runtime.foundLevels().values().stream()
                .mapToDouble(level -> level.modifier().durationMultiplier())
                .reduce(1D, (left, right) -> left * right);
        int levelModifiedDuration = (int) Math.round(recipe.getRecipeTotalTickTime() * levelMultiplier);
        return Math.max(1, IntegrationTypeHelper.asInt(
                IntegrationTypeHelper.applyDuration(modifiers, levelModifiedDuration)));
    }

    private int currentGameTime() {
        if (controller.getLevel() == null) return 0;
        long gameTime = controller.getLevel().getGameTime();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, gameTime));
    }

    private long capabilityGameTime() {
        return controller.getLevel() == null ? 0L : controller.getLevel().getGameTime();
    }

    private ExecutionStatus failure(FailureReason reason, FailurePhase phase, Map<String, String> details) {
        Identifier source = MMCR.id("crafting_runtime");
        return ExecutionStatus.blocked(source, source,
                FailureOccurrence.at(reason, source, phase,
                        activeRecipe == null ? null : activeRecipe.getRecipe().id(), null, details));
    }

    private record AsyncTickPreparation(ControllerRuntimeSnapshot runtime, MachineBehaviorContext machineContext,
                                        RecipeTickContext tickContext) {
    }

    private static String failureUnloc(ExecutionStatus status) {
        if (status == null) return "";
        FailureReason reason = status.reason();
        return (reason == null ? BuiltinFailureReasons.UNKNOWN : reason).translationKey();
    }
}
