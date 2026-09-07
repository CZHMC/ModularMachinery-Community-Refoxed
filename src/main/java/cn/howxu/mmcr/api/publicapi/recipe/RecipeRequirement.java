package cn.howxu.mmcr.api.publicapi.recipe;


import cn.howxu.mmcr.api.publicapi.RecipeApi;
import com.google.gson.JsonElement;
import net.minecraft.resources.Identifier;
/** Public immutable recipe requirement boundary.
 * @author howxu <dev@howxu.cn>
 */
public sealed interface RecipeRequirement permits ItemRequirement, FluidRequirement, EnergyRequirement,
        SmartInterfaceRequirement, CustomRecipeIo {
    /**
     * Creates a validated codec-backed recipe IO declaration.
     *
     * @param typeId registered requirement or output type identifier
     * @param ioType recipe IO direction
     * @param payload codec payload
     * @return validated custom recipe IO
     */
    static CustomRecipeIo custom(Identifier typeId, RecipeIo ioType,
                                 JsonElement payload) {
        return RecipeApi.custom(typeId, ioType, payload);
    }
}
