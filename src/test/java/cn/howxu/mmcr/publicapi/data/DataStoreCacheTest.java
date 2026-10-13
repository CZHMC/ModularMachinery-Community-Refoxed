package cn.howxu.mmcr.publicapi.data;

import cn.howxu.mmcr.api.data.DataStorage;
import cn.howxu.mmcr.api.data.view.DataValue;
import cn.howxu.mmcr.internal.api.facade.data.StorageAdapters;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.howxu.mmcr.api.data.view.DataStorage.view;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies Java API projections are shared without caching mutable inputs or stale transaction data.
 * @author howxu <dev@howxu.cn>
 */
class DataStoreCacheTest {
    @Test
    void fresh_adapters_share_store_snapshots_and_nested_collections() {
        DataStorage storage = new DataStorage();
        DataStore first = StorageAdapters.wrap(view(storage));
        first.set("payload", DataKey.map(Map.of("entries", DataKey.list(List.of(DataKey.of("original"))))));
        var snapshot = first.values();
        var payload = snapshot.get("payload").asMap().orElseThrow();
        var entries = payload.get("entries").asList().orElseThrow();
        DataStore second = StorageAdapters.wrap(view((Object) storage));

        assertThat(second.values()).isSameAs(snapshot);
        assertThat(second.get("payload").orElseThrow().asMap().orElseThrow()).isSameAs(payload);
        assertThat(first.get("payload").orElseThrow().asMap().orElseThrow().get("entries").asList().orElseThrow())
                .isSameAs(entries);
        assertThat(entries.getFirst().stringValue()).isEqualTo("original");
        assertThatThrownBy(snapshot::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(payload::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(entries::clear).isInstanceOf(UnsupportedOperationException.class);

        first.set("other", DataKey.of("changed"));
        assertThat(second.values()).isNotSameAs(snapshot);
        assertThat(second.values().get("payload").asMap().orElseThrow()).isSameAs(payload);
    }

    @Test
    void writes_and_removals_refresh_new_adapters_without_changing_old_snapshots() {
        DataStorage storage = new DataStorage();
        DataStore store = StorageAdapters.wrap(view(storage));
        store.set("state", DataKey.of("original"));
        var original = store.values();
        store.set("state", DataKey.of("original"));
        assertThat(store.remove("missing")).isEmpty();
        assertThat(store.values()).isSameAs(original);

        store.set("state", DataKey.of("replacement"));
        var replacement = StorageAdapters.wrap(view(storage)).values();
        assertThat(replacement.get("state").stringValue()).isEqualTo("replacement");
        assertThat(original.get("state").stringValue()).isEqualTo("original");
        assertThat(store.remove("state").orElseThrow().stringValue()).isEqualTo("replacement");
        assertThat(StorageAdapters.wrap(view(storage)).values()).isEmpty();
        assertThat(store.get("state")).isEmpty();
        assertThat(replacement).containsKey("state");
    }

    @Test
    void transaction_reads_refresh_after_rollback_and_root_commit() {
        DataStorage storage = new DataStorage();
        DataStore store = StorageAdapters.wrap(view(storage));
        store.set("state", DataKey.of("original"));
        var original = store.values();
        try (Transaction transaction = Transaction.openRoot()) {
            var borrowed = StorageAdapters.wrap(transaction);
            store.set("state", DataKey.of("temporary"), borrowed);
            assertThat(StorageAdapters.wrap(view(storage)).values().get("state").stringValue()).isEqualTo("temporary");
        }
        assertThat(store.values().get("state").stringValue()).isEqualTo("original");
        try (Transaction transaction = Transaction.openRoot()) {
            var borrowed = StorageAdapters.wrap(transaction);
            store.set("state", DataKey.of("committed"), borrowed);
            assertThat(store.get("state").orElseThrow().stringValue()).isEqualTo("committed");
            transaction.commit();
        }
        assertThat(StorageAdapters.wrap(view(storage)).values().get("state").stringValue()).isEqualTo("committed");
        assertThat(original.get("state").stringValue()).isEqualTo("original");
    }

    @Test
    void nested_rollback_restores_outer_map_and_list_projections() {
        DataStorage storage = new DataStorage();
        DataStore store = StorageAdapters.wrap(view(storage));
        try (Transaction root = Transaction.openRoot()) {
            store.set("payload", DataKey.map(Map.of("entries", DataKey.list(List.of(DataKey.of("outer"))))),
                    StorageAdapters.wrap(root));
            var outer = store.values().get("payload").asMap().orElseThrow();
            var outerEntries = outer.get("entries").asList().orElseThrow();
            List<DataKey> innerEntries;
            try (Transaction nested = Transaction.open(root)) {
                store.set("payload", DataKey.map(Map.of("entries", DataKey.list(List.of(DataKey.of("inner"))))),
                        StorageAdapters.wrap(nested));
                innerEntries = store.values().get("payload").asMap().orElseThrow().get("entries").asList().orElseThrow();
                assertThat(innerEntries.getFirst().stringValue()).isEqualTo("inner");
            }
            DataStore fresh = StorageAdapters.wrap(view(storage));
            assertThat(fresh.get("payload").orElseThrow().asMap().orElseThrow()).isSameAs(outer);
            assertThat(fresh.values().get("payload").asMap().orElseThrow().get("entries").asList().orElseThrow())
                    .isSameAs(outerEntries);
            assertThat(outerEntries.getFirst().stringValue()).isEqualTo("outer");
            assertThat(innerEntries.getFirst().stringValue()).isEqualTo("inner");
            root.commit();
        }
        assertThat(store.get("payload").orElseThrow().asMap().orElseThrow().get("entries")
                .asList().orElseThrow().getFirst().stringValue()).isEqualTo("outer");
    }

    @Test
    void equal_store_contents_remain_isolated() {
        DataStore first = StorageAdapters.wrap(view(new DataStorage()));
        DataStore second = StorageAdapters.wrap(view(new DataStorage()));
        first.set("state", DataKey.of("original"));
        second.set("state", DataKey.of("original"));
        var secondSnapshot = second.values();
        assertThat(first.values()).isEqualTo(secondSnapshot).isNotSameAs(secondSnapshot);

        first.set("state", DataKey.of("changed"));

        assertThat(second.values()).isSameAs(secondSnapshot);
        assertThat(second.get("state").orElseThrow().stringValue()).isEqualTo("original");
    }

    @Test
    void general_map_conversion_does_not_cache_mutable_sources() {
        Map<String, DataValue> source = new LinkedHashMap<>();
        source.put("state", DataValue.of("original"));
        var original = StorageAdapters.values(source);
        source.put("state", DataValue.of("changed"));

        assertThat(StorageAdapters.values(source).get("state").stringValue()).isEqualTo("changed");
        assertThat(original.get("state").stringValue()).isEqualTo("original");
    }
}
