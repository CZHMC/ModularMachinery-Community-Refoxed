package cn.howxu.mmcr.compat.appliedenergistics2.loaded;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import cn.howxu.mmcr.internal.assembly.StructureItemSink;
import cn.howxu.mmcr.internal.assembly.StructureItemSource;
import cn.howxu.mmcr.internal.assembly.StructureItemStorage;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * Item-only terminal view of an AE2 network storage.
 *
 * @author howxu <dev@howxu.cn>
 */
final class AE2NetworkStructureItemStorage implements StructureItemStorage, StructureItemSource, StructureItemSink {
    private final MEStorage storage;
    private final IActionSource actionSource;

    AE2NetworkStructureItemStorage(MEStorage storage, IActionSource actionSource) {
        this.storage = storage;
        this.actionSource = actionSource;
    }

    @Override
    public StructureItemSource source() {
        return this;
    }

    @Override
    public StructureItemSink sink() {
        return this;
    }

    @Override
    public List<ItemStack> copyStacks() {
        KeyCounter available = storage.getAvailableStacks();
        List<ItemStack> stacks = new ArrayList<>();
        for (var entry : available) {
            if (entry.getKey() instanceof AEItemKey itemKey && entry.getLongValue() > 0L) {
                stacks.add(itemKey.toStack((int) Math.min(Integer.MAX_VALUE, entry.getLongValue())));
            }
        }
        return stacks;
    }

    @Override
    public boolean canExtractAll(List<ItemStack> requirements) {
        for (ItemStack requirement : requirements) {
            if (requirement.isEmpty()) continue;
            if (storage.extract(AEItemKey.of(requirement), requirement.getCount(), Actionable.SIMULATE, actionSource)
                    != requirement.getCount()) return false;
        }
        return true;
    }

    @Override
    public boolean extractAll(List<ItemStack> requirements) {
        if (!canExtractAll(requirements)) return false;
        for (ItemStack requirement : requirements) {
            if (requirement.isEmpty()) continue;
            if (storage.extract(AEItemKey.of(requirement), requirement.getCount(), Actionable.MODULATE, actionSource)
                    != requirement.getCount()) throw new IllegalStateException("ME network changed during terminal extraction");
        }
        return true;
    }

    @Override
    public boolean accept(ItemStack stack) {
        if (stack.isEmpty()) return true;
        AEItemKey item = AEItemKey.of(stack);
        if (storage.insert(item, stack.getCount(), Actionable.SIMULATE, actionSource) != stack.getCount()) return false;
        long inserted = storage.insert(item, stack.getCount(), Actionable.MODULATE, actionSource);
        if (inserted == stack.getCount()) return true;
        if (inserted > 0L) storage.extract(item, inserted, Actionable.MODULATE, actionSource);
        return false;
    }
}
