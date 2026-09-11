package cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.helpers.externalstorage.GenericStackInv;
import cn.howxu.mmcr.api.capability.storage.ResourceStorage;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Generic transactional MMCR view over one AE2 generic stack inventory.
 *
 * @param <R> resource type exposed by the view
 * @author howxu <dev@howxu.cn>
 */
public abstract class AE2ResourceStorage<R> implements ResourceStorage<R> {
    protected final GenericStackInv inventory;
    private final AE2KeyAdapter<R> adapter;

    protected AE2ResourceStorage(GenericStackInv inventory, AE2KeyAdapter<R> adapter) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @Override
    public Class<R> resourceType() {
        return adapter.resourceType();
    }

    @Override
    public final Object reservationIdentity() {
        return inventory;
    }

    @Override
    public int size() {
        return inventory.size();
    }

    @Override
    @Nullable
    public R resource(int slot) {
        AEKey key = inventory.getKey(slot);
        return key == null ? null : adapter.toResource(key).orElse(null);
    }

    @Override
    public long amount(int slot) {
        return inventory.getAmount(slot);
    }

    @Override
    public long capacity(int slot, @Nullable R resource) {
        checkSlot(slot);
        if (resource == null) return inventory.getCapacity(adapter.keyType());
        if (!adapter.resourceType().isInstance(resource)) return 0L;
        AEKey key = adapter.toKey(resource);
        return key == null ? inventory.getCapacity(adapter.keyType()) : inventory.getMaxAmount(key);
    }

    @Override
    public boolean isValid(int slot, R resource) {
        checkSlot(slot);
        AEKey key = keyOf(resource);
        AEKey current = inventory.getKey(slot);
        return inventory.isAllowedIn(slot, key) && (current == null || current.equals(key));
    }

    @Override
    public long insert(int slot, R resource, long amount, TransactionContext transaction) {
        AEKey key = checkedKey(slot, resource, amount);
        if (!inventory.canInsert() || amount == 0L || !inventory.isAllowedIn(slot, key)) return 0L;

        AEKey current = inventory.getKey(slot);
        if (current != null && !current.equals(key)) return 0L;

        long currentAmount = inventory.getAmount(slot);
        long maximum = inventory.getMaxAmount(key);
        long available = maximum <= currentAmount ? 0L : maximum - currentAmount;
        long inserted = Math.min(amount, available);
        if (inserted == 0L) return 0L;

        inventory.updateSnapshots(transaction);
        inventory.setStack(slot, new GenericStack(key, currentAmount + inserted));
        return Math.max(0L, inventory.getAmount(slot) - currentAmount);
    }

    @Override
    public long extract(int slot, R resource, long amount, TransactionContext transaction) {
        AEKey key = checkedKey(slot, resource, amount);
        if (!inventory.canExtract() || amount == 0L || !inventory.isAllowedIn(slot, key)) return 0L;

        AEKey current = inventory.getKey(slot);
        if (current == null || !current.equals(key)) return 0L;

        long currentAmount = inventory.getAmount(slot);
        long extracted = Math.min(amount, currentAmount);
        if (extracted == 0L) return 0L;

        inventory.updateSnapshots(transaction);
        long remaining = currentAmount - extracted;
        inventory.setStack(slot, remaining == 0L ? null : new GenericStack(key, remaining));
        return Math.max(0L, currentAmount - inventory.getAmount(slot));
    }

    private AEKey checkedKey(int slot, R resource, long amount) {
        checkSlot(slot);
        if (amount < 0L) throw new IllegalArgumentException("Expected value to be non-negative: " + amount);
        return keyOf(resource);
    }

    private AEKey keyOf(R resource) {
        if (resource == null || !adapter.resourceType().isInstance(resource)) {
            throw new IllegalArgumentException("Expected resource of type " + adapter.resourceType().getName());
        }
        AEKey key = adapter.toKey(resource);
        if (key == null) throw new IllegalArgumentException("Expected resource to be non-empty: " + resource);
        return key;
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= inventory.size()) throw new IndexOutOfBoundsException(slot);
    }
}
