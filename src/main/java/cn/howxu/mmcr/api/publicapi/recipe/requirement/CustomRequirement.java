package cn.howxu.mmcr.api.publicapi.recipe.requirement;

import com.google.gson.JsonElement;
import net.minecraft.resources.Identifier;

/**
 * Public codec-backed custom recipe requirement.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface CustomRequirement extends MachineRequirement {
    Identifier typeId();

    JsonElement payload();
}
