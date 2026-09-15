package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.api.machine.Machine;
import cn.howxu.mmcr.api.machine.MachineRegistry;
import cn.howxu.mmcr.api.recipe.ActiveMachineRecipe;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.MachineRecipeCatalog;
import cn.howxu.mmcr.api.recipe.RecipeRegistry;
import cn.howxu.mmcr.internal.runtime.ControllerRuntimeSnapshot;
import cn.howxu.mmcr.internal.runtime.CraftingRuntime;
import cn.howxu.mmcr.internal.async.AsyncContinuation;
import cn.howxu.mmcr.internal.runtime.MachineWorkMode;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Recipe thread used by a normal machine controller.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MachineRecipeThread extends RecipeThread {
    private final boolean controlsControllerRuntime;
    private @Nullable MachineRecipe lastRecipe;
    private long lastRecipeStructureVersion = Long.MIN_VALUE;
    private long lastRecipeCapabilityVersion = Long.MIN_VALUE;
    private long lastRecipeModifierVersion = Long.MIN_VALUE;
    private long lastRecipeComponentStateVersion = Long.MIN_VALUE;
    private long lastRecipeCatalogVersion = Long.MIN_VALUE;
    private boolean restartPending;
    private @Nullable AsyncContinuation pendingAsyncFinishRestart;

    public MachineRecipeThread(MachineControllerBlockEntity controller) {
        super(controller);
        controlsControllerRuntime = false;
    }

    public MachineRecipeThread(MachineControllerBlockEntity controller, CraftingRuntime runtime) {
        super(controller, runtime);
        controlsControllerRuntime = true;
    }

    @Override
    protected void onStarted() {
        rememberStartedRecipe();
        if (controlsControllerRuntime) controller.onNormalRecipeThreadStarted();
    }

    @Override
    protected void onFinished() {
        if (lastRecipe != null && !recipeBelongsToCurrentMachine(lastRecipe)) {
            clearLastRecipe();
        } else if (lastRecipe != null && lastRecipeCatalogVersion != Long.MIN_VALUE) {
            MachineRecipeCatalog catalog = RecipeRegistry.catalogForMachine(currentMachine());
            MachineRecipe current = catalog.recipes().stream()
                    .filter(candidate -> lastRecipe.id().equals(candidate.id()))
                    .findFirst().orElse(null);
            if (lastRecipeCatalogVersion != catalog.version()
                    && (current == null || !ActiveMachineRecipe.sameDefinition(lastRecipe, current, registryAccess()))) {
                clearLastRecipe();
            }
        }
        if (controlsControllerRuntime) controller.onNormalRecipeThreadFinished();
    }

    @Override
    protected void onRecipeFinished() {
        markRecipeFinished();
        if (controller.activeWorkMode() != MachineWorkMode.ASYNC || controller.lockedRecipeId() != null) return;
        MachineRecipe restartRecipe = consumeRestartRecipe(RecipeRegistry.catalogForMachine(currentMachine()).recipes(),
                controller.getMaxParallelism(), controller.currentRuntimeSnapshot().structure().version());
        if (restartRecipe != null) {
            pendingAsyncFinishRestart = prepareAsyncStartContinuation(restartRecipe, controller.getMaxParallelism(),
                    controller.currentRuntimeSnapshot().structure().version(), null);
        }
    }

    @Override
    protected void onRecipeFailure() {
        if (controlsControllerRuntime) controller.onNormalRecipeThreadFinished();
    }

    @Override
    protected void onStartSearchFailed(@Nullable ExecutionStatus failure) {
        super.onStartSearchFailed(failure);
        if (controlsControllerRuntime) controller.onNormalRecipeThreadSearchFailed();
    }

    public void rememberStartedRecipe() {
        MachineRecipe recipe = runtime.recipe();
        if (recipe == null) return;
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        lastRecipe = recipe;
        lastRecipeStructureVersion = snapshot.structure().version();
        lastRecipeCapabilityVersion = snapshot.capabilityVersion();
        lastRecipeModifierVersion = snapshot.modifierVersion();
        lastRecipeComponentStateVersion = snapshot.stateVersion();
        lastRecipeCatalogVersion = RecipeRegistry.catalogForMachine(currentMachine()).version();
    }

    public void markRecipeFinished() {
        restartPending = lastRecipe != null;
    }

    public boolean tryRestartLastRecipe(List<MachineRecipe> candidates, long availableParallelism,
                                        long structureVersion) {
        MachineRecipe retryRecipe = consumeRestartRecipe(candidates, availableParallelism, structureVersion);
        return retryRecipe != null && startRecipe(retryRecipe, availableParallelism, structureVersion);
    }

    public @Nullable MachineRecipe consumeRestartRecipe(List<MachineRecipe> candidates, long availableParallelism,
                                                         long structureVersion) {
        if (!restartPending) return null;
        restartPending = false;
        MachineRecipe retryRecipe = lastRecipe;
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        boolean canRestart = retryRecipe != null && availableParallelism > 0
                && lastRecipeStructureVersion == structureVersion
                && lastRecipeCapabilityVersion == snapshot.capabilityVersion()
                && lastRecipeModifierVersion == snapshot.modifierVersion()
                && lastRecipeComponentStateVersion == snapshot.stateVersion()
                && recipeBelongsToCurrentMachine(retryRecipe)
                && candidatesForMachine(candidates).contains(retryRecipe);
        return canRestart ? retryRecipe : null;
    }

    @Override
    protected @Nullable AsyncContinuation consumeAsyncFinishRestart() {
        AsyncContinuation restart = pendingAsyncFinishRestart;
        pendingAsyncFinishRestart = null;
        return restart;
    }

    private List<MachineRecipe> candidatesForMachine(List<MachineRecipe> candidates) {
        Identifier recipePoolId = MachineRegistry.recipePoolForMachine(currentMachine());
        if (recipePoolId == null || candidates == null) return List.of();
        return candidates.stream().filter(recipe -> recipe != null && recipePoolId.equals(recipe.recipePoolId())).toList();
    }

    private boolean recipeBelongsToCurrentMachine(MachineRecipe recipe) {
        Identifier recipePoolId = MachineRegistry.recipePoolForMachine(currentMachine());
        return recipePoolId != null && recipePoolId.equals(recipe.recipePoolId());
    }

    private Machine currentMachine() {
        ControllerRuntimeSnapshot snapshot = controller.currentRuntimeSnapshot();
        return snapshot.structure().machine() == null
                ? snapshot.structure().configuredMachine() : snapshot.structure().machine();
    }

    private @Nullable net.minecraft.core.HolderLookup.Provider registryAccess() {
        return controller.getLevel() == null ? null : controller.getLevel().registryAccess();
    }

    private void clearLastRecipe() {
        lastRecipe = null;
        lastRecipeStructureVersion = Long.MIN_VALUE;
        lastRecipeCapabilityVersion = Long.MIN_VALUE;
        lastRecipeModifierVersion = Long.MIN_VALUE;
        lastRecipeComponentStateVersion = Long.MIN_VALUE;
        lastRecipeCatalogVersion = Long.MIN_VALUE;
        restartPending = false;
    }
}
