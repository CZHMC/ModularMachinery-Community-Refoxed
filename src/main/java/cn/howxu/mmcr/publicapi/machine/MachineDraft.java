package cn.howxu.mmcr.publicapi.machine;

import cn.howxu.mmcr.publicapi.behavior.MachineContext;
import cn.howxu.mmcr.publicapi.behavior.RecipeHooks;
import cn.howxu.mmcr.publicapi.behavior.TickHooks;
import cn.howxu.mmcr.publicapi.network.RequestHandler;
import cn.howxu.mmcr.publicapi.network.FailureHandler;
import cn.howxu.mmcr.publicapi.recipe.modifier.SmartModifierSpec;
import cn.howxu.mmcr.publicapi.runtime.RecipeFailureMode;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/** Producer-created configuration; consumers must not implement it. @author howxu <dev@howxu.cn> */
@ApiStatus.NonExtendable
public interface MachineDraft {
    MachineDraft displayNameKey(String key);
    MachineDraft recipePool(Identifier... ids);
    MachineDraft controller(Consumer<ControllerOptions> configure);
    MachineDraft appearance(Consumer<AppearanceOptions> configure);
    MachineDraft factory(Consumer<FactoryOptions> configure);
    MachineDraft recipeBehavior(Consumer<RecipeHooks> configure);
    MachineDraft tickBehavior(Consumer<TickHooks> configure);
    MachineDraft preServerTick(Consumer<MachineContext> callback);
    MachineDraft postServerTick(Consumer<MachineContext> callback);
    MachineDraft role(MachineKind kind);
    MachineDraft acceptedModule(Identifier id);
    MachineDraft networkInterface(int maxCount, int maxConnections);
    MachineDraft allowNetworkMachine(Identifier id);
    MachineDraft maxParallelism(long amount);
    MachineDraft parallelizable(boolean enabled);
    MachineDraft allowModifiers();
    MachineDraft allowModifiers(boolean enabled);
    MachineDraft allowMultithreading();
    MachineDraft allowMultithreading(boolean enabled);
    MachineDraft maxParallelAmount(int amount);
    MachineDraft smartInterface(SmartInterfaceSpec type);
    MachineDraft shareSmartInterfaces();
    MachineDraft shareSmartInterfaces(boolean enabled);
    MachineDraft smartInterfaceModifier(SmartModifierSpec modifier);
    MachineDraft runningSound(Identifier id);
    MachineDraft finishSound(Identifier id);
    MachineDraft failureAction(RecipeFailureMode mode);
    MachineDraft requestProcess(Identifier id, RequestHandler handler);
    MachineDraft requestFailed(Identifier id, FailureHandler handler);
    MachineSpec build();
}
