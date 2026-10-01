package cn.howxu.mmcr.publicapi.recipe.requirement;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;
/** Library-produced machine level requirement. @author howxu <dev@howxu.cn> */
@ApiStatus.NonExtendable
public interface LevelRequirementSpec extends RequirementSpec { Identifier typeId(); Identifier levelId(); }
