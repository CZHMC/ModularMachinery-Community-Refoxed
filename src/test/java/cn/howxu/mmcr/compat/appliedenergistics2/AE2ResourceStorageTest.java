package cn.howxu.mmcr.compat.appliedenergistics2;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeySlotFilter;
import appeng.helpers.externalstorage.GenericStackInv;
import cn.howxu.mmcr.api.capability.plan.PlanningReservations;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.FluidResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.storage.ItemResourceStorage;
import cn.howxu.mmcr.compat.appliedenergistics2.loaded.adapter.AE2KeyAdapter;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the AE2-backed resource storage adapters preserve MMCR storage semantics.
 *
 * @author howxu <dev@howxu.cn>
 */
class AE2ResourceStorageTest {
    @BeforeAll
    static void setup() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void itemAdapterConvertsBothDirectionsAndRejectsFluidKey() {
        ItemResource item = ItemResource.of(Items.IRON_INGOT);
        AE2KeyAdapter<ItemResource> adapter = ItemResourceStorage.adapter();

        assertThat(adapter.toResource(adapter.toKey(item))).contains(item);
        assertThat(adapter.toResource(AEFluidKey.of(Fluids.WATER))).isEmpty();
    }

    @Test
    void committedInsertAndRollbackUseGenericStackSnapshots() {
        GenericStackInv inventory = inventory(1);
        ItemResourceStorage storage = new ItemResourceStorage(inventory);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 4L, transaction)).isEqualTo(4L);
        }
        assertThat(storage.amount(0)).isZero();
        assertThat(inventory.getStack(0)).isNull();

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(storage.insert(0, iron, 4L, transaction)).isEqualTo(4L);
            transaction.commit();
        }
        assertThat(storage.resource(0)).isEqualTo(iron);
        assertThat(storage.amount(0)).isEqualTo(4L);
    }

    @Test
    void itemAndFluidViewsShareTheSameReservationIdentity() {
        GenericStackInv inventory = inventory(1);

        assertThat(new ItemResourceStorage(inventory).reservationIdentity())
                .isSameAs(new FluidResourceStorage(inventory).reservationIdentity());
    }

    @Test
    void sharedViewsCannotReserveTheSameSlotTwice() {
        GenericStackInv inventory = inventory(1);
        var item = new ItemResourceStorage(inventory);
        var fluid = new FluidResourceStorage(inventory);
        PlanningReservations reservations = new PlanningReservations();

        assertThat(reservations.reserveInsert(item, 0, ItemResource.of(Items.IRON_INGOT), 1L)).isTrue();
        assertThat(reservations.reserveInsert(fluid, 0, FluidResource.of(Fluids.WATER), 1L)).isFalse();
    }

    @Test
    void storageRespectsKeyCapacityAndSupportedTypeFilter() {
        GenericStackInv inventory = inventory(1);
        inventory.setCapacity(AEKeyType.items(), 2L);
        ItemResourceStorage items = new ItemResourceStorage(inventory);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(items.insert(0, ItemResource.of(Items.IRON_INGOT), 5L, transaction)).isEqualTo(2L);
            transaction.commit();
        }

        GenericStackInv itemOnly = new GenericStackInv(
                Set.of(AEKeyType.items()), null, GenericStackInv.Mode.STORAGE, 1);
        FluidResourceStorage fluids = new FluidResourceStorage(itemOnly);
        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(fluids.insert(0, FluidResource.of(Fluids.WATER), 1L, transaction)).isZero();
        }
    }

    @Test
    void storageRejectsAFilteredKeyWithoutChangingTheSlot() {
        GenericStackInv inventory = new FilteredInventory((slot, key) -> key instanceof AEFluidKey);
        ItemResourceStorage items = new ItemResourceStorage(inventory);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(items.insert(0, iron, 1L, transaction)).isZero();
            transaction.commit();
        }

        assertThat(inventory.getStack(0)).isNull();
    }

    @Test
    void insertRespectsInventoryCanInsertPermission() {
        GenericStackInv inventory = new DirectionalInventory(false, true);
        ItemResourceStorage items = new ItemResourceStorage(inventory);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(items.insert(0, ItemResource.of(Items.IRON_INGOT), 1L, transaction)).isZero();
            transaction.commit();
        }

        assertThat(inventory.getStack(0)).isNull();
    }

    @Test
    void extractRespectsInventoryCanExtractPermission() {
        GenericStackInv inventory = new DirectionalInventory(true, false);
        ItemResource iron = ItemResource.of(Items.IRON_INGOT);
        inventory.setStack(0, new GenericStack(AEItemKey.of(iron), 3L));
        ItemResourceStorage items = new ItemResourceStorage(inventory);

        try (Transaction transaction = Transaction.openRoot()) {
            assertThat(items.extract(0, iron, 1L, transaction)).isZero();
            transaction.commit();
        }

        assertThat(items.amount(0)).isEqualTo(3L);
    }

    @Test
    void wrongKeyInInventoryDoesNotBecomeAnItemResource() {
        GenericStackInv inventory = inventory(1);
        inventory.setStack(0, new GenericStack(AEFluidKey.of(Fluids.WATER), 1L));

        assertThat(new ItemResourceStorage(inventory).resource(0)).isNull();
    }

    private static GenericStackInv inventory(int size) {
        return new GenericStackInv(
                Set.of(AEKeyType.items(), AEKeyType.fluids()), null, GenericStackInv.Mode.STORAGE, size);
    }

    private static final class FilteredInventory extends GenericStackInv {
        private FilteredInventory(AEKeySlotFilter filter) {
            super(Set.of(AEKeyType.items(), AEKeyType.fluids()), null, Mode.STORAGE, 1);
            setFilter(filter);
        }
    }

    private static final class DirectionalInventory extends GenericStackInv {
        private final boolean canInsert;
        private final boolean canExtract;

        private DirectionalInventory(boolean canInsert, boolean canExtract) {
            super(Set.of(AEKeyType.items(), AEKeyType.fluids()), null, Mode.STORAGE, 1);
            this.canInsert = canInsert;
            this.canExtract = canExtract;
        }

        @Override
        public boolean canInsert() {
            return canInsert;
        }

        @Override
        public boolean canExtract() {
            return canExtract;
        }
    }
}
