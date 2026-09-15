package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.RecipeSearchResult;
import cn.howxu.mmcr.api.recipe.RecipeSearchTask;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.api.machine.Machine;
import cn.howxu.mmcr.api.recipe.helper.CraftingStatus;
import cn.howxu.mmcr.api.publicapi.machine.RecipeStartContext;
import cn.howxu.mmcr.internal.multiblock.SharedIoCoordinator;
import cn.howxu.mmcr.internal.multiblock.StructureClaimRegistry;
import cn.howxu.mmcr.internal.async.MachineAsyncCoordinator;
import cn.howxu.mmcr.internal.async.MainThreadStep;
import cn.howxu.mmcr.internal.runtime.AsyncCraftingExecution;
import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;
import cn.howxu.mmcr.internal.runtime.CraftingRuntime;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import cn.howxu.mmcr.internal.runtime.ResourceAvailabilityNotifier;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Scheduling and event adapter for one crafting runtime.
 *
 * @author howxu <dev@howxu.cn>
 */
public abstract class RecipeThread {
    public enum Status { IDLE, WORKING, WAITING, FAILED }

    protected final MachineControllerBlockEntity controller;
    protected final CraftingRuntime runtime;
    private boolean startPending;
    private @Nullable MachineRecipe pendingStartRecipe;
    private @Nullable StructureClaimRegistry.ResourceDomain pendingStartDomain;
    private long nextStartToken;
    private long pendingStartToken;
    private long pendingStartStructureVersion;
    private long pendingStartCapabilityVersion;
    private long pendingStartModifierVersion;
    private long pendingStartComponentStateVersion;
    private long pendingStartCatalogVersion;
    private @Nullable Identifier pendingStartRecipePoolId;
    private @Nullable RecipeSearchContextKey pendingStartSearchContextKey;
    private boolean tickPending;
    private @Nullable StructureClaimRegistry.ResourceDomain pendingTickDomain;
    private long nextTickToken;
    private long pendingTickToken;

    private record StartSnapshot(ControllerRuntimeSnapshot runtime, long structureVersion, long catalogVersion,
                                 Identifier recipePoolId, @Nullable RecipeSearchContextKey searchContextKey) {
    }

    private record PendingAsyncStart(ServerLevel level, StructureClaimRegistry.ResourceDomain domain,
                                     MachineRecipe recipe, long parallelism, StartSnapshot snapshot) {
    }

    private @Nullable PendingAsyncStart pendingAsyncStart;
    private @Nullable RecipeStartContext.ExecutionSnapshot pendingAsyncStartExecution;
    private boolean asyncFinishPrepared;

    protected RecipeThread(MachineControllerBlockEntity controller) {
        if (controller == null) throw new IllegalArgumentException("controller must not be null");
        this.controller = controller;
        this.runtime = new CraftingRuntime(controller, controller.componentRuntime());
    }

    public boolean searchAndStartRecipe(List<MachineRecipe> candidates, long availableParallelism, long structureVersion) {
        return searchAndStartRecipe(candidates, availableParallelism, structureVersion, null);
    }

    protected boolean searchAndStartRecipe(List<MachineRecipe> candidates, long availableParallelism,
                                           long structureVersion, @Nullable Identifier lockedRecipeId) {
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        Machine machine = snapshot.structure().machine() == null
                ? snapshot.structure().configuredMachine() : snapshot.structure().machine();
        Identifier machineId = machine == null ? null : machine.registryName();
        if (machineId == null || availableParallelism <= 0) return false;
        List<MachineRecipe> machineCandidates = candidatesForPool(candidates, machineId);
        RecipeSearchResult result;
        try {
            result = new RecipeSearchTask(snapshot, machineId, structureVersion,
                    availableParallelism, machineCandidates, lockedRecipeId, controller.componentRuntime().capabilities()).compute();
        } catch (RuntimeException exception) {
            controller.clearPendingConflictStart();
            onStartSearchFailed(null);
            return false;
        }
        if (!result.success()) {
            controller.clearPendingConflictStart();
            onStartSearchFailed(result.failure());
            return false;
        }
        if (controller.shouldDelayConflictProneStart(result)) return false;
        return startRecipe(result.recipe(), availableParallelism, structureVersion);
    }

    protected boolean searchAndStartRecipe(FactorySearchContext context, List<MachineRecipe> candidates,
                                           long structureVersion, @Nullable Identifier lockedRecipeId) {
        if (context == null) return false;
        ControllerRuntimeSnapshot snapshot = context.snapshot();
        Machine machine = snapshot.structure().machine() == null
                ? snapshot.structure().configuredMachine() : snapshot.structure().machine();
        Identifier machineId = machine == null ? null : machine.registryName();
        if (machineId == null || context.maxParallelism() <= 0) return false;
        List<MachineRecipe> machineCandidates = candidatesForPool(candidates, machineId);
        RecipeSearchResult result;
        try {
            result = new RecipeSearchTask(snapshot, machineId, structureVersion,
                    context.maxParallelism(), machineCandidates, lockedRecipeId,
                    context.capabilities(), context.modifiers()).compute();
        } catch (RuntimeException exception) {
            controller.clearPendingConflictStart();
            onStartSearchFailed(null);
            return false;
        }
        if (!result.success()) {
            controller.clearPendingConflictStart();
            onStartSearchFailed(result.failure());
            return false;
        }
        if (controller.shouldDelayConflictProneStart(result)) return false;
        return startRecipe(result.recipe(), context.maxParallelism(), structureVersion, context);
    }

    private static List<MachineRecipe> candidatesForPool(List<MachineRecipe> candidates, Identifier machineId) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Identifier recipePoolId = MachineRegistry.recipePoolForMachine(machineId);
        if (recipePoolId == null) return List.of();
        return candidates.stream().filter(recipe -> recipe != null
                && recipePoolId.equals(recipe.recipePoolId())).toList();
    }

    protected boolean startRecipe(MachineRecipe next, long requestedParallelism, long structureVersion) {
        return startRecipe(next, requestedParallelism, structureVersion, null);
    }

    protected boolean startRecipe(MachineRecipe next, long requestedParallelism, long structureVersion,
                                  @Nullable FactorySearchContext context) {
        if (next == null || requestedParallelism <= 0) return false;
        ControllerRuntimeSnapshot currentSnapshot = controller.currentRuntimeSnapshot();
        Identifier recipePoolId = recipePoolForMachine(currentSnapshot);
        if (recipePoolId == null || !recipePoolId.equals(next.recipePoolId())) return false;
        StructureClaimRegistry.ResourceDomain domain = controller.resourceDomain();
        if (controller.getLevel() instanceof ServerLevel serverLevel && domain != null) {
            if (pendingAsyncStart != null) return false;
            ControllerRuntimeSnapshot startRuntime = context == null ? currentSnapshot : context.snapshot();
            StartSnapshot startSnapshot = new StartSnapshot(startRuntime, structureVersion,
                    context == null ? currentCatalogVersion() : context.catalogVersion(), recipePoolId,
                    searchContextKeyForStart());
            MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.get(serverLevel);
            MachineAsyncCoordinator.TaskKey taskKey = new MachineAsyncCoordinator.TaskKey(controller.getBlockPos(),
                    serverLevel.getGameTime(), MachineWorkMode.ASYNC, asyncLaneId());
            pendingAsyncStart = new PendingAsyncStart(serverLevel, domain, next, requestedParallelism, startSnapshot);
            pendingAsyncStartExecution = null;
            return coordinator.submit(taskKey, AsyncCraftingExecution.start(asyncLaneId(), startSnapshot.catalogVersion()),
                    this::executeAsyncMainStep);
        }
        CraftingStatus state = runtime.start(next, requestedParallelism);
        if (!state.isCrafting()) {
            controller.clearRecipeScreenText(laneId());
            onStartFailed(searchContextKeyForStart());
            return false;
        }
        onStarted();
        return true;
    }

    private boolean requestStart(ServerLevel level, StructureClaimRegistry.ResourceDomain domain,
                                   MachineRecipe next, long requestedParallelism,
                                   StartSnapshot startSnapshot, RecipeStartContext.ExecutionSnapshot preparedStart,
                                   Runnable completion) {
        long token = ++nextStartToken;
        startPending = true;
        pendingStartRecipe = next;
        pendingStartDomain = domain;
        pendingStartToken = token;
        pendingStartStructureVersion = startSnapshot.structureVersion();
        pendingStartCapabilityVersion = startSnapshot.runtime().capabilityVersion();
        pendingStartModifierVersion = startSnapshot.runtime().modifierVersion();
        pendingStartComponentStateVersion = startSnapshot.runtime().stateVersion();
        pendingStartCatalogVersion = startSnapshot.catalogVersion();
        pendingStartRecipePoolId = startSnapshot.recipePoolId();
        pendingStartSearchContextKey = startSnapshot.searchContextKey();
        SharedIoCoordinator.get(level).enqueue(new SharedIoCoordinator.StartRequest(
                domain,
                new SharedIoCoordinator.LaneKey(controller.getBlockPos(), laneId()),
                startSnapshot.structureVersion(),
                startSnapshot.runtime().stateVersion(),
                requestedParallelism,
                requested -> {
                    if (!isPendingStart(token, next) || runtime.active()) return 0L;
                    CraftingStatus state = runtime.start(next, requested, preparedStart);
                    if (!state.isCrafting()) {
                        controller.clearRecipeScreenText(laneId());
                        RecipeSearchContextKey failureKey = pendingStartSearchContextKey;
                        clearPendingStart(token, next);
                        onStartFailed(failureKey);
                        controller.syncRecipeRuntimeFailure(runtime);
                        completion.run();
                        return 0L;
                    }
                    return runtime.parallelism();
                },
                granted -> {
                    if (!isPendingStart(token, next) || !runtime.active()) return;
                    clearPendingStart(token, next);
                    onStarted();
                    controller.syncRecipeRuntimeFailure(runtime);
                },
                () -> {
                    boolean valid = isPendingStart(token, next) && domain.equals(controller.resourceDomain());
                    if (!valid) completion.run();
                    return valid;
                },
                () -> controller.currentRuntimeSnapshot().structure().version(),
                () -> controller.currentRuntimeSnapshot().stateVersion(),
                pendingStartCatalogVersion,
                this::currentCatalogVersion,
                completion
        ));
        return true;
    }

    private boolean isPendingStart(long token, MachineRecipe recipe) {
        if (!startPending || pendingStartToken != token || pendingStartRecipe != recipe) return false;
        if (controller.isRedstonePaused()) {
            invalidatePendingStart(token, recipe);
            return false;
        }
        if (pendingStartDomain == null || !pendingStartDomain.equals(controller.resourceDomain())) {
            invalidatePendingStart(token, recipe);
            return false;
        }
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        if (snapshot.structure().version() != pendingStartStructureVersion
                || snapshot.capabilityVersion() != pendingStartCapabilityVersion
                || snapshot.modifierVersion() != pendingStartModifierVersion
                || snapshot.stateVersion() != pendingStartComponentStateVersion) {
            invalidatePendingStart(token, recipe);
            return false;
        }
        if (pendingStartRecipePoolId == null
                || !pendingStartRecipePoolId.equals(recipePoolForMachine(snapshot))
                || !pendingStartRecipePoolId.equals(recipe.recipePoolId())) {
            invalidatePendingStartForCatalog(token, recipe);
            return false;
        }
        if (currentCatalogVersion() != pendingStartCatalogVersion) {
            invalidatePendingStartForCatalog(token, recipe);
            return false;
        }
        return true;
    }

    private long currentCatalogVersion() {
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        Machine machine = snapshot.structure().machine() == null
                ? snapshot.structure().configuredMachine() : snapshot.structure().machine();
        return RecipeRegistry.catalogForMachine(machine).version();
    }

    private void invalidatePendingStart(long token, MachineRecipe recipe) {
        clearPendingStart(token, recipe);
        runtime.invalidate();
        controller.clearRecipeScreenText(laneId());
        controller.syncRecipeRuntimeFailure(runtime);
    }

    private void clearPendingStart(long token, MachineRecipe recipe) {
        if (!startPending || pendingStartToken != token || pendingStartRecipe != recipe) return;
        startPending = false;
        pendingStartRecipe = null;
        pendingStartDomain = null;
        pendingStartToken = 0L;
        pendingStartStructureVersion = Long.MIN_VALUE;
        pendingStartCapabilityVersion = Long.MIN_VALUE;
        pendingStartModifierVersion = Long.MIN_VALUE;
        pendingStartComponentStateVersion = Long.MIN_VALUE;
        pendingStartCatalogVersion = Long.MIN_VALUE;
        pendingStartRecipePoolId = null;
        pendingStartSearchContextKey = null;
    }

    private void invalidatePendingStartForCatalog(long token, MachineRecipe recipe) {
        clearPendingStart(token, recipe);
        runtime.invalidate();
        controller.clearRecipeScreenText(laneId());
        onPendingStartCatalogChanged();
        controller.syncRecipeRuntimeFailure(runtime);
    }

    public void tick() {
        if (startPending && !isPendingStart(pendingStartToken, pendingStartRecipe)) {
            clearPendingStart(pendingStartToken, pendingStartRecipe);
            controller.clearRecipeScreenText(laneId());
        }
        if (!runtime.active()) return;
        if (tickPending && !validateCurrentRuntime(pendingTickToken, pendingTickDomain)) clearPendingTick();
        if (tickPending) return;

        StructureClaimRegistry.ResourceDomain domain = controller.resourceDomain();
        if (controller.getLevel() instanceof ServerLevel level && domain != null) {
            if (runtime.finishPending()) {
                if (!runtime.shouldRetryFinish()) return;
                long token = ++nextTickToken;
                pendingTickToken = token;
                requestFinish(level, domain, token);
            } else {
                requestTick(level, domain);
            }
            return;
        }
        boolean wasActive = runtime.active();
        runtime.tick();
        if (runtime.finishPending()) runtime.finish();
        completeIfFinished(wasActive);
    }

    private void requestTick(ServerLevel level, StructureClaimRegistry.ResourceDomain domain) {
        tickPending = true;
        pendingTickDomain = domain;
        long token = ++nextTickToken;
        pendingTickToken = token;
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        long structureVersion = snapshot.structure().version();
        long catalogVersion = currentCatalogVersion();
        SharedIoCoordinator.get(level).enqueue(new SharedIoCoordinator.TickRequest(
                domain,
                new SharedIoCoordinator.LaneKey(controller.getBlockPos(), laneId()),
                structureVersion,
                snapshot.stateVersion(),
                () -> {
                      if (!validateCurrentRuntime(token, domain)) return false;
                      return MachineAsyncCoordinator.get(level).submit(new MachineAsyncCoordinator.TaskKey(
                              controller.getBlockPos(), level.getGameTime(), MachineWorkMode.ASYNC, asyncLaneId()),
                              AsyncCraftingExecution.tick(asyncLaneId(), catalogVersion), this::executeAsyncMainStep);
                  },
                  () -> {
                      boolean valid = catalogVersion == currentCatalogVersion() && validateCurrentRuntime(token, domain);
                      if (!valid) clearPendingTick();
                      return valid;
                  },
                  () -> controller.currentRuntimeSnapshot().structure().version(),
                  () -> controller.currentRuntimeSnapshot().stateVersion(),
                  currentCatalogVersion(),
                  this::currentCatalogVersion,
                  () -> { }
          ));
    }

    /** Runs only from {@link AsyncCraftingExecution}'s explicit main-thread intent commit step. */
    public void completeAsyncTick(AsyncRequirementPlanner.PlanResult ignoredPlan) {
        if (!tickPending || !runtime.active()) return;
        if (!validateCurrentRuntime(pendingTickToken, pendingTickDomain)) return;
        boolean wasActive = runtime.active();
        runtime.completeAsyncTick(ignoredPlan);
        if (runtime.finishPending()) {
            if (runtime.shouldRetryFinish()) {
                clearPendingTick();
            } else {
                clearPendingTick();
            }
        } else {
            completeIfFinished(wasActive);
        }
        controller.syncRecipeRuntimeFailure(runtime);
    }

    private void requestFinish(ServerLevel level, StructureClaimRegistry.ResourceDomain domain, long token) {
        tickPending = true;
        pendingTickDomain = domain;
        MachineAsyncCoordinator coordinator = MachineAsyncCoordinator.get(level);
        MachineAsyncCoordinator.TaskKey key = new MachineAsyncCoordinator.TaskKey(controller.getBlockPos(),
                level.getGameTime(), MachineWorkMode.ASYNC, asyncLaneId());
        if (!coordinator.submit(key, AsyncCraftingExecution.finish(asyncLaneId(), currentCatalogVersion()),
                this::executeAsyncMainStep)) clearPendingTick();
    }

    private void enqueueFinish(ServerLevel level, StructureClaimRegistry.ResourceDomain domain, long token,
                               MachineAsyncCoordinator.TaskKey key, long catalogVersion) {
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        long structureVersion = snapshot.structure().version();
        SharedIoCoordinator.get(level).enqueue(new SharedIoCoordinator.FinishRequest(
                domain,
                new SharedIoCoordinator.LaneKey(controller.getBlockPos(), laneId()),
                structureVersion,
                snapshot.stateVersion(),
                () -> {
                    if (!validateCurrentRuntime(token, domain)) return false;
                    boolean wasActive = runtime.active();
                    runtime.finish();
                    completeIfFinished(wasActive);
                    controller.syncRecipeRuntimeFailure(runtime);
                    return true;
                 },
                  () -> {
                      boolean valid = catalogVersion == currentCatalogVersion() && validateCurrentRuntime(token, domain);
                      if (!valid) {
                          clearPendingTick();
                          MachineAsyncCoordinator.get(level).resume(key);
                      }
                      return valid;
                  },
                  () -> controller.currentRuntimeSnapshot().structure().version(),
                  () -> controller.currentRuntimeSnapshot().stateVersion(),
                   catalogVersion,
                  this::currentCatalogVersion,
                  () -> {
                      controller.notifyResourceAvailability(ResourceAvailabilityNotifier.Reason.OUTPUT_CAPACITY, null);
                      MachineAsyncCoordinator.get(level).resume(key);
                  }
          ));
    }

    private MainThreadStep.Result executeAsyncMainStep(MachineAsyncCoordinator.TaskKey key, MainThreadStep step) {
        if (!validateAsyncMainStep(key, step)) {
            return MainThreadStep.Result.failure(new IllegalStateException("Async continuation validation failed"));
        }
        if (step instanceof MainThreadStep.Lifecycle lifecycle) {
            if (lifecycle.kind() == MainThreadStep.Kind.BEFORE_START) {
                PendingAsyncStart pending = pendingAsyncStart;
                if (pending == null) {
                    return MainThreadStep.Result.failure(new IllegalStateException("Missing async start lifecycle"));
                }
                pendingAsyncStartExecution = runtime.prepareAsyncStart(pending.recipe(), pending.parallelism());
            } else if (lifecycle.kind() == MainThreadStep.Kind.BEFORE_FINISH) {
                asyncFinishPrepared = runtime.prepareAsyncFinish();
            } else if (lifecycle.kind() == MainThreadStep.Kind.RECIPE_TICK) {
                if (!runtime.prepareAsyncTick()) {
                    completeAsyncTick(new AsyncRequirementPlanner.PlanResult(List.of(), List.of()));
                    return validateAsyncMainStep(key, step) ? MainThreadStep.Result.success()
                            : MainThreadStep.Result.failure(new IllegalStateException("Async tick preparation became stale"));
                }
            }
            return validateAsyncMainStep(key, step) ? MainThreadStep.Result.success()
                    : MainThreadStep.Result.failure(new IllegalStateException("Async lifecycle became stale"));
        }
        if (step instanceof MainThreadStep.CapabilityTick capabilityTick) {
            if (!runtime.executeAsyncCapabilityTick(capabilityTick.phase())) {
                runtime.discardAsyncTickPreparation();
                completeAsyncTick(new AsyncRequirementPlanner.PlanResult(List.of(), List.of()));
            }
            return validateAsyncMainStep(key, step) ? MainThreadStep.Result.success()
                    : MainThreadStep.Result.failure(new IllegalStateException("Async capability tick became stale"));
        }
        if (step instanceof MainThreadStep.ScreenTextFlush screenTextFlush) {
            if (screenTextFlush.source() == MainThreadStep.Kind.RECIPE_TICK) {
                AsyncRequirementPlanner.PreparedPlan preparedPlan = runtime.prepareAsyncTickPlan();
                runtime.flushAsyncScreenText();
                return preparedPlan == null ? MainThreadStep.Result.success() : MainThreadStep.Result.value(preparedPlan);
            }
            runtime.flushAsyncScreenText();
            return MainThreadStep.Result.success();
        }
        if (step instanceof MainThreadStep.SharedIoRequest request) {
            if (request.kind() == MainThreadStep.Kind.BEFORE_START) {
                PendingAsyncStart pending = pendingAsyncStart;
                RecipeStartContext.ExecutionSnapshot preparedStart = pendingAsyncStartExecution;
                pendingAsyncStart = null;
                pendingAsyncStartExecution = null;
                if (pending == null) {
                    return MainThreadStep.Result.failure(new IllegalStateException("Missing async start continuation"));
                }
                if (preparedStart == null) return MainThreadStep.Result.success();
                requestStart(pending.level(), pending.domain(), pending.recipe(), pending.parallelism(), pending.snapshot(), preparedStart,
                        () -> MachineAsyncCoordinator.get(pending.level()).resume(key));
                return MainThreadStep.Result.pending();
            }
            if (request.kind() == MainThreadStep.Kind.BEFORE_FINISH) {
                if (controller.getLevel() instanceof ServerLevel level && pendingTickDomain != null) {
                    if (!asyncFinishPrepared) {
                        clearPendingTick();
                        controller.syncRecipeRuntimeFailure(runtime);
                        return MainThreadStep.Result.success();
                    }
                    asyncFinishPrepared = false;
                    enqueueFinish(level, pendingTickDomain, pendingTickToken, key, request.catalogVersion());
                    return MainThreadStep.Result.pending();
                }
                return MainThreadStep.Result.failure(new IllegalStateException("Missing async finish continuation"));
            }
        }
        if (step instanceof MainThreadStep.UnsupportedRequirement unsupported) {
            if (controller.getLevel() instanceof ServerLevel level && pendingTickDomain != null) {
                enqueueAsyncTickIntent(level, pendingTickDomain, pendingTickToken, key,
                        unsupported.catalogVersion(), unsupported.intent());
                return MainThreadStep.Result.pending();
            }
            return MainThreadStep.Result.failure(new IllegalStateException("Missing async tick fallback context"));
        }
        if (step instanceof MainThreadStep.IntentCommit intent) {
            if (controller.getLevel() instanceof ServerLevel level && pendingTickDomain != null) {
                enqueueAsyncTickIntent(level, pendingTickDomain, pendingTickToken, key, intent.catalogVersion(), intent.intent());
                return MainThreadStep.Result.pending();
            }
            return MainThreadStep.Result.failure(new IllegalStateException("Missing async intent context"));
        }
        MainThreadStep.Result result = step.execute();
        return result instanceof MainThreadStep.Result.Pending || validateAsyncMainStep(key, step)
                ? result : MainThreadStep.Result.failure(new IllegalStateException("Async continuation became stale"));
    }

    private boolean validateAsyncMainStep(MachineAsyncCoordinator.TaskKey key, MainThreadStep step) {
        if (!controller.getBlockPos().equals(key.controllerPos())) return false;
        if ((step instanceof MainThreadStep.SharedIoRequest request && request.kind() == MainThreadStep.Kind.BEFORE_START)
                || (step instanceof MainThreadStep.Lifecycle lifecycle && lifecycle.kind() == MainThreadStep.Kind.BEFORE_START)
                || (step instanceof MainThreadStep.ScreenTextFlush screenTextFlush
                && screenTextFlush.source() == MainThreadStep.Kind.BEFORE_START)) {
            PendingAsyncStart pending = pendingAsyncStart;
            if (pending == null || controller.isRedstonePaused() || !pending.domain().equals(controller.resourceDomain())) {
                return false;
            }
            StartSnapshot snapshot = pending.snapshot();
            ControllerRuntimeSnapshot current = controller.currentRuntimeSnapshot();
            long catalogVersion = step instanceof MainThreadStep.SharedIoRequest request
                    ? request.catalogVersion() : step instanceof MainThreadStep.Lifecycle lifecycle
                    ? lifecycle.catalogVersion() : ((MainThreadStep.ScreenTextFlush) step).catalogVersion();
            return catalogVersion == currentCatalogVersion()
                    && snapshot.catalogVersion() == currentCatalogVersion()
                    && snapshot.structureVersion() == current.structure().version()
                    && snapshot.runtime().capabilityVersion() == current.capabilityVersion()
                    && snapshot.runtime().modifierVersion() == current.modifierVersion()
                    && snapshot.runtime().stateVersion() == current.stateVersion()
                    && snapshot.recipePoolId().equals(recipePoolForMachine(current))
                    && snapshot.recipePoolId().equals(pending.recipe().recipePoolId());
        }
        long catalogVersion = step instanceof MainThreadStep.IntentCommit intent ? intent.catalogVersion()
                : step instanceof MainThreadStep.UnsupportedRequirement unsupported ? unsupported.catalogVersion()
                : step instanceof MainThreadStep.SharedIoRequest request ? request.catalogVersion()
                : step instanceof MainThreadStep.Lifecycle lifecycle ? lifecycle.catalogVersion()
                : step instanceof MainThreadStep.CapabilityTick capabilityTick ? capabilityTick.catalogVersion()
                : step instanceof MainThreadStep.ScreenTextFlush screenTextFlush ? screenTextFlush.catalogVersion()
                : Long.MIN_VALUE;
        return catalogVersion == Long.MIN_VALUE || catalogVersion == currentCatalogVersion()
                && validateCurrentRuntime(pendingTickToken, pendingTickDomain);
    }

    private void enqueueAsyncTickIntent(ServerLevel level, StructureClaimRegistry.ResourceDomain domain, long token,
                                        MachineAsyncCoordinator.TaskKey key, long catalogVersion,
                                        AsyncRequirementPlanner.PlanResult intent) {
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        SharedIoCoordinator.get(level).enqueue(new SharedIoCoordinator.TickRequest(domain,
                new SharedIoCoordinator.LaneKey(controller.getBlockPos(), laneId()), snapshot.structure().version(),
                snapshot.stateVersion(), () -> {
                    if (!validateCurrentRuntime(token, domain)) return false;
                    completeAsyncTick(intent);
                    return true;
                }, () -> {
                    boolean valid = catalogVersion == currentCatalogVersion() && validateCurrentRuntime(token, domain);
                    if (!valid) {
                        clearPendingTick();
                        MachineAsyncCoordinator.get(level).resume(key);
                    }
                    return valid;
                }, () -> controller.currentRuntimeSnapshot().structure().version(),
                () -> controller.currentRuntimeSnapshot().stateVersion(), catalogVersion, this::currentCatalogVersion,
                () -> MachineAsyncCoordinator.get(level).resume(key)));
    }

    private boolean validateCurrentRuntime(long token, @Nullable StructureClaimRegistry.ResourceDomain domain) {
        if (!tickPending || pendingTickToken != token) return false;
        if (controller.isRedstonePaused()) {
            clearPendingTick();
            return false;
        }
        if (!runtime.active()) {
            clearPendingTick();
            return false;
        }
        if (!runtime.versionsCurrent()) {
            boolean wasActive = runtime.active();
            runtime.tick();
            completeIfFinished(wasActive);
            controller.syncRecipeRuntimeFailure(runtime);
            return false;
        }
        if (domain == null || !domain.equals(controller.resourceDomain())) {
            clearPendingTick();
            return false;
        }
        return true;
    }

    private void clearPendingTick() {
        tickPending = false;
        pendingTickDomain = null;
        pendingTickToken = 0L;
    }

    private void completeIfFinished(boolean wasActive) {
        clearPendingTick();
        if (wasActive && !runtime.active()) {
            if (runtime.failure() == null) {
                onFinished();
                onRecipeFinished();
            } else {
                onRecipeFailure();
            }
            controller.clearRecipeScreenText(laneId());
        }
    }

    public void invalidate() {
        runtime.invalidate();
        controller.clearRecipeScreenText(laneId());
        pendingAsyncStart = null;
        pendingAsyncStartExecution = null;
        asyncFinishPrepared = false;
        startPending = false;
        pendingStartRecipe = null;
        pendingStartDomain = null;
        pendingStartToken = 0L;
        pendingStartStructureVersion = Long.MIN_VALUE;
        pendingStartCapabilityVersion = Long.MIN_VALUE;
        pendingStartModifierVersion = Long.MIN_VALUE;
        pendingStartComponentStateVersion = Long.MIN_VALUE;
        pendingStartCatalogVersion = Long.MIN_VALUE;
        pendingStartRecipePoolId = null;
        pendingStartSearchContextKey = null;
          clearPendingTick();
    }

    public void invalidateForSmartInterfaceChange() {
        runtime.invalidateForSmartInterfaceChange();
        if (runtime.active()) return;
        if (startPending) clearPendingStart(pendingStartToken, pendingStartRecipe);
        clearPendingTick();
        controller.clearRecipeScreenText(laneId());
    }

    protected void onStartSearchFailed(@Nullable ExecutionStatus failure) {
        runtime.recordSearchFailure(failure);
    }

    protected abstract void onStarted();
    protected abstract void onFinished();
    protected void onRecipeFinished() { }
    protected void onRecipeFailure() { }
    protected void onStartFailed() { }
    protected void onStartFailed(@Nullable RecipeSearchContextKey contextKey) { onStartFailed(); }
    protected void onPendingStartCatalogChanged() { }
    protected @Nullable RecipeSearchContextKey searchContextKeyForStart() { return null; }
    protected String laneId() { return "base"; }
    public final String asyncLaneId() { return laneId(); }

    private static @Nullable Identifier recipePoolForMachine(ControllerRuntimeSnapshot snapshot) {
        Machine machine = snapshot.structure().machine() == null
                ? snapshot.structure().configuredMachine() : snapshot.structure().machine();
        return MachineRegistry.recipePoolForMachine(machine);
    }

    public Status getStatus() {
        if (runtime.active()) return runtime.finishPending() ? Status.WAITING : Status.WORKING;
        return runtime.failure() == null ? Status.IDLE : Status.FAILED;
    }
    public boolean isIdle() { return !startPending && !runtime.active(); }
    public boolean isStartPending() { return startPending; }
    public @Nullable MachineRecipe getPendingStartRecipe() { return pendingStartRecipe; }
    public long usedParallelism() { return runtime.parallelism(); }
    public CraftingRuntime runtime() { return runtime; }

}
