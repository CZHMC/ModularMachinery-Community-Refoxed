package cn.howxu.mmcr.internal.recipe;

import cn.howxu.mmcr.api.capability.status.ExecutionStatus;
import cn.howxu.mmcr.internal.runtime.CraftingRuntime;
import cn.howxu.mmcr.internal.tile.MachineControllerBlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Recipe thread used by a normal machine controller.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class MachineRecipeThread extends RecipeThread {
    private final boolean controlsControllerRuntime;

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
        if (controlsControllerRuntime) controller.onNormalRecipeThreadStarted();
    }

    @Override
    protected void onFinished() {
        if (controlsControllerRuntime) controller.onNormalRecipeThreadFinished();
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
}
