package cn.howxu.mmcr.api.capability;

import cn.howxu.mmcr.util.IOType;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable set of directions supported by a capability.
 *
 * @author howxu <dev@howxu.cn>
 */
public record CapabilityDirections(Set<IOType> values) {
    public CapabilityDirections {
        values = Set.copyOf(Objects.requireNonNull(values, "values"));
        if (values.isEmpty()) throw new IllegalArgumentException("values must not be empty");
    }

    public static CapabilityDirections of(IOType... values) {
        return new CapabilityDirections(Set.of(values));
    }

    public static CapabilityDirections input() {
        return of(IOType.INPUT);
    }

    public static CapabilityDirections output() {
        return of(IOType.OUTPUT);
    }

    public static CapabilityDirections bidirectional() {
        return of(IOType.INPUT, IOType.OUTPUT);
    }

    public boolean supports(IOType direction) {
        return values.contains(Objects.requireNonNull(direction, "direction"));
    }
}
