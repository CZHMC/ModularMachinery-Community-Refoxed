package cn.howxu.mmcr.api.publicapi.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** Public view of a machine's typed data storage.
 * @author howxu <dev@howxu.cn>
 */
public final class DataStorage {
    private final cn.howxu.mmcr.api.data.DataStorage storage;

    private DataStorage(cn.howxu.mmcr.api.data.DataStorage storage) {
        this.storage = storage;
    }

    public static DataStorage view(Object storage) {
        if (!(storage instanceof cn.howxu.mmcr.api.data.DataStorage dataStorage)) {
            throw new IllegalArgumentException("storage must be a machine data storage");
        }
        return new DataStorage(dataStorage);
    }

    public Optional<DataValue> get(String key) {
        return storage.get(key).map(DataValue::fromInternal);
    }

    public boolean contains(String key) { return storage.contains(key); }

    public Map<String, DataValue> values() {
        Map<String, DataValue> values = new LinkedHashMap<>();
        storage.values().forEach((key, value) -> values.put(key, DataValue.fromInternal(value)));
        return Map.copyOf(values);
    }

    public void set(String key, DataValue value) { storage.set(key, DataValue.toInternal(value)); }

    public boolean set(String key, DataValue value, Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        return storage.set(key, DataValue.toInternal(value), transaction.context);
    }

    public Optional<DataValue> remove(String key) { return storage.remove(key).map(DataValue::fromInternal); }

    /** Public write transaction shared with a machine I/O commit.
     * @author howxu <dev@howxu.cn>
     */
    public static final class Transaction {
        private final TransactionContext context;

        private Transaction(TransactionContext context) {
            this.context = context;
        }

        public static Transaction view(Object context) {
            if (!(context instanceof TransactionContext transaction)) {
                throw new IllegalArgumentException("context must be a transaction");
            }
            return new Transaction(transaction);
        }
    }
}
