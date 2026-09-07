package cn.howxu.mmcr.api.publicapi.network;

import cn.howxu.mmcr.api.publicapi.data.DataValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable values carried by a public machine network request.
 * @author howxu <dev@howxu.cn>
 */
public final class RequestBody {
    private final Map<String, DataValue> values;

    private RequestBody(Map<String, DataValue> values) { this.values = values; }

    public static RequestBody of(Map<String, DataValue> values) {
        Map<String, DataValue> copy = new LinkedHashMap<>();
        Objects.requireNonNull(values, "values").forEach((key, value) -> {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("request body key must not be blank");
            copy.put(key, Objects.requireNonNull(value, "request body value"));
        });
        return new RequestBody(Map.copyOf(copy));
    }

    public Map<String, DataValue> values() { return values; }
    public Optional<DataValue> get(String key) { return Optional.ofNullable(values.get(key)); }

    public static RequestBody fromInternal(cn.howxu.mmcr.api.network.RequestBody body) {
        Map<String, DataValue> converted = new LinkedHashMap<>();
        body.values().forEach((key, value) -> converted.put(key, DataValue.fromInternal(value)));
        return of(converted);
    }

    cn.howxu.mmcr.api.network.RequestBody toInternal() {
        Map<String, cn.howxu.mmcr.api.data.DataValue> converted = new LinkedHashMap<>();
        values.forEach((key, value) -> converted.put(key, toInternal(value)));
        return cn.howxu.mmcr.api.network.RequestBody.of(converted);
    }

    private static cn.howxu.mmcr.api.data.DataValue toInternal(DataValue value) {
        Object publicValue = value.value();
        if (publicValue instanceof Boolean valueBoolean) return cn.howxu.mmcr.api.data.DataValue.of(valueBoolean);
        if (publicValue instanceof String valueString) return cn.howxu.mmcr.api.data.DataValue.of(valueString);
        if (publicValue instanceof Byte valueByte) return cn.howxu.mmcr.api.data.DataValue.of(valueByte);
        if (publicValue instanceof Short valueShort) return cn.howxu.mmcr.api.data.DataValue.of(valueShort);
        if (publicValue instanceof Integer valueInt) return cn.howxu.mmcr.api.data.DataValue.of(valueInt);
        if (publicValue instanceof Long valueLong) return cn.howxu.mmcr.api.data.DataValue.of(valueLong);
        if (publicValue instanceof Float valueFloat) return cn.howxu.mmcr.api.data.DataValue.of(valueFloat);
        if (publicValue instanceof Double valueDouble) return cn.howxu.mmcr.api.data.DataValue.of(valueDouble);
        if (publicValue instanceof java.math.BigInteger valueBigInteger) return cn.howxu.mmcr.api.data.DataValue.of(valueBigInteger);
        if (publicValue instanceof java.math.BigDecimal valueBigDecimal) return cn.howxu.mmcr.api.data.DataValue.of(valueBigDecimal);
        if (publicValue instanceof java.util.List<?> values) return cn.howxu.mmcr.api.data.DataValue.list(values.stream()
                .map(DataValue.class::cast).map(RequestBody::toInternal).toList());
        Map<String, cn.howxu.mmcr.api.data.DataValue> converted = new LinkedHashMap<>();
        value.asMap().orElseThrow().forEach((key, entry) -> converted.put(key, toInternal(entry)));
        return cn.howxu.mmcr.api.data.DataValue.map(converted);
    }
}
