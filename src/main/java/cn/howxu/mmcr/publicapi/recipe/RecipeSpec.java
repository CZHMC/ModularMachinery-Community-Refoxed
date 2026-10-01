package cn.howxu.mmcr.publicapi.recipe;
import cn.howxu.mmcr.publicapi.recipe.requirement.RequirementSpec;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
/** Library-produced recipe declaration, distinct from runtime RecipeView. @author howxu <dev@howxu.cn> */
@ApiStatus.NonExtendable
public interface RecipeSpec {
    Identifier id(); Identifier recipePoolId(); int tickTime(); int priority(); int maxThreads();
    boolean cancelRecipeOnPerTickFailure(); boolean parallelized(); boolean allowPartialOutputs();
    List<ItemInputSpec> itemInputs(); List<FluidInputSpec> fluidInputs(); List<EnergyRateSpec> energyInputs();
    List<ItemOutputSpec> itemOutputs(); List<FluidOutputSpec> fluidOutputs(); List<EnergyRateSpec> energyOutputs();
    List<RequirementSpec> requirements(); List<CustomIoSpec> customOutputs(); List<Identifier> modifierIds();
    Set<HostConstraint> requiredHosts(); Set<Identifier> requiredHostIds();
}
