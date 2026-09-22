package cn.howxu.mmcr.api.recipe.helper;

import java.util.Collections;
import java.util.List;

public record RequirementComponents(List<ProcessingComponent> components) {

    public RequirementComponents(List<ProcessingComponent> components) {
        this.components = Collections.unmodifiableList(components);
    }

    public static RequirementComponents empty() {
        return new RequirementComponents(Collections.emptyList());
    }

    public boolean isEmpty() {
        return components.isEmpty();
    }
}
