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

    long outputCapacity(Identifier chemicalId);
}
