package cn.howxu.mmcr.api.publicapi.machine;

import cn.howxu.mmcr.api.publicapi.recipe.modifier.RecipeModifier;

/** Public mapping from a smart-interface value to a recipe modifier.
 * @author howxu <dev@howxu.cn>
 */
public record SmartInterfaceModifier(String interfaceType, String target, String scope,
        boolean affectsChance, float minValue, float maxValue, float atMin, float atMax,
        RecipeModifier.Operation operation) {
    public SmartInterfaceModifier {
        if (interfaceType == null || interfaceType.isBlank()) throw new IllegalArgumentException("interfaceType blank");
        if (target == null || target.isBlank()) throw new IllegalArgumentException("target blank");
        if (!Float.isFinite(minValue) || !Float.isFinite(maxValue)
                || !Float.isFinite(atMin) || !Float.isFinite(atMax)) {
            throw new IllegalArgumentException("smart interface modifier values must be finite");
        }
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope blank");
        operation = operation == null ? RecipeModifier.Operation.MULTIPLY : operation;
    }

    public SmartInterfaceModifier(String interfaceType, String target, RecipeModifier.IOType io,
            boolean affectsChance, float minValue, float maxValue, float atMin, float atMax,
            RecipeModifier.Operation operation) {
        this(interfaceType, target, io == RecipeModifier.IOType.OUTPUT ? "output" : "input",
                affectsChance, minValue, maxValue, atMin, atMax, operation);
    }

    public static SmartInterfaceModifier duration(String type, float min, float max, float atMin, float atMax,
            RecipeModifier.Operation operation) {
        return new SmartInterfaceModifier(type, "duration", "input", false,
                min, max, atMin, atMax, operation);
    }

    public static SmartInterfaceModifier energy(String type, float min, float max, float atMin, float atMax,
            RecipeModifier.Operation operation) {
        return new SmartInterfaceModifier(type, "energy", "input", false,
                min, max, atMin, atMax, operation);
    }

    public RecipeModifier.IOType io() {
        return "output".equals(scope) ? RecipeModifier.IOType.OUTPUT : RecipeModifier.IOType.INPUT;
    }
}
