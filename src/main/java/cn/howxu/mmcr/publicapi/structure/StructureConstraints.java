package cn.howxu.mmcr.publicapi.structure;

import cn.howxu.mmcr.internal.api.facade.structure.StructureAdapters;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.ApiStatus;

/** Factory-owned modifier placements and level slots; not a consumer SPI.
 * @author howxu <dev@howxu.cn>
 */
@ApiStatus.NonExtendable
public interface StructureConstraints {
    static StructureConstraints empty() { return StructureAdapters.emptyConstraints(); }
    static Builder builder() { return StructureAdapters.constraints(); }
    Map<Character, List<ModifierPlacementView>> modifierReplacements();
    Map<Character, Identifier> levelSlots();

    /** Factory-owned placement view.
     * @author howxu <dev@howxu.cn>
     */
    @ApiStatus.NonExtendable
    interface ModifierPlacementView {
        Identifier modifierId();
        BlockCondition replacement();
    }

    /** Factory-owned configuration handle.
     * @author howxu <dev@howxu.cn>
     */
    @ApiStatus.NonExtendable
    interface Builder {
        Builder modifier(char symbol, Identifier modifierId);
        Builder modifier(char symbol, Identifier modifierId, BlockCondition replacement);
        Builder levelSlot(char symbol, Identifier typeId);
        StructureConstraints build();
    }
}
