package cn.howxu.mmcr.api.publicapi.recipe.requirement;

import cn.howxu.mmcr.api.publicapi.recipe.RecipeIo;
import cn.howxu.mmcr.api.publicapi.recipe.RecipeRequirement;

/**
 * Public machine recipe requirement.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface MachineRequirement extends RecipeRequirement {
    RecipeIo io();
}
