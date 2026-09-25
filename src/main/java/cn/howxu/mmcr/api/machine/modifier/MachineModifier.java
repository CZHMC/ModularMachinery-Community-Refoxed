package cn.howxu.mmcr.api.machine.modifier;

import cn.howxu.mmcr.api.recipe.modifier.RecipeModifier;

import java.util.Locale;
import java.util.Objects;

/** Validated declaration that changes machine scheduling or recipe execution properties.
 * @author howxu <dev@howxu.cn>
 */
public sealed interface MachineModifier permits MachineModifier.Numeric, MachineModifier.Parallelized {
    static Numeric numeric(String target, String scope, double value, String operation, boolean affectsChance) {
        ModifierTarget modifierTarget = ModifierTarget.parse(target, scope);
        if (!modifierTarget.numeric()) {
            throw new IllegalArgumentException("Machine modifier target is not numeric: " + target);
        }
        return new Numeric(modifierTarget, value, parseOperation(operation), affectsChance);
    }

    static Parallelized parallelized(boolean value) {
        return new Parallelized(value);
    }

    private static RecipeModifier.Operation parseOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Machine modifier operation must not be blank");
        }
        try {
            return RecipeModifier.Operation.valueOf(operation.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown machine modifier operation: " + operation, exception);
        }
    }

    record Numeric(ModifierTarget target, double value, RecipeModifier.Operation operation,
                   boolean affectsChance) implements MachineModifier {
        public Numeric {
            target = Objects.requireNonNull(target, "target");
            operation = Objects.requireNonNull(operation, "operation");
            if (!target.numeric()) {
                throw new IllegalArgumentException("Machine modifier target is not numeric: " + target.key());
            }
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Machine modifier value must be finite");
            }
            if (affectsChance && !target.supportsChance()) {
                throw new IllegalArgumentException("Machine modifier target does not support chance: " + target.key());
            }
        }
    }

    record Parallelized(boolean value) implements MachineModifier { }
}
