package cn.howxu.mmcr.api.compat.mekanism;

import cn.howxu.mmcr.api.capability.facet.CapabilityFacet;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Mekanism-neutral read access to a chemical capability.
 *
 * @author howxu <dev@howxu.cn>
 */
public interface ChemicalViewFacet extends CapabilityFacet {
    Optional<Identifier> chemicalId();

    long amount();

    boolean matchesTag(Identifier tagId);

    default boolean matchesTag(String tagId) {
        return matchesTag(tagId == null ? null : Identifier.parse(tagId));
    }

    long outputCapacity(Identifier chemicalId);

    default long outputCapacity(String chemicalId) {
        return outputCapacity(chemicalId == null ? null : Identifier.parse(chemicalId));
    }
}
