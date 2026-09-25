package cn.howxu.mmcr.api.recipe.modifier;

import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class DynamicModifierReplacement extends AbstractModifierReplacement {

    public DynamicModifierReplacement(String modifierName, List<MachineModifier> modifiers, List<String> description, ItemStack descriptiveStack) {
        super(modifierName, modifiers, description, descriptiveStack);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        return obj instanceof DynamicModifierReplacement other && baseEquals(other);
    }

    @Override
    public int hashCode() {
        return baseHashCode();
    }
}
