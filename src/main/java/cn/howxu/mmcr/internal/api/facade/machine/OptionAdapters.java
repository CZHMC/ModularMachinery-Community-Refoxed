package cn.howxu.mmcr.internal.api.facade.machine;

import cn.howxu.mmcr.api.machine.definition.AppearanceSpec;
import cn.howxu.mmcr.api.machine.definition.ControllerSpec;
import cn.howxu.mmcr.api.machine.definition.FactorySpec;
import cn.howxu.mmcr.publicapi.machine.AppearanceOptions;
import cn.howxu.mmcr.publicapi.machine.ControllerOptions;
import cn.howxu.mmcr.publicapi.machine.FactoryOptions;
import java.util.List;
import net.minecraft.resources.Identifier;

/** Direct option delegates and immutable spec views. @author howxu <dev@howxu.cn> */
final class OptionAdapters {
    private OptionAdapters() {}

    /** @author howxu <dev@howxu.cn> */
    static final class Controller implements ControllerOptions {
        private final ControllerSpec.Builder delegate;
        Controller(ControllerSpec.Builder delegate) { this.delegate = delegate; }
        @Override public ControllerOptions id(Identifier id) { delegate.id(id); return this; }
        @Override public ControllerOptions textures(Identifier front, Identifier other) { delegate.textures(front, other); return this; }
        @Override public ControllerOptions textures(Identifier front, Identifier side, Identifier top, Identifier bottom) { delegate.textures(front, side, top, bottom); return this; }
        @Override public ControllerOptions frontTexture(Identifier id) { delegate.frontTexture(id); return this; }
        @Override public ControllerOptions sideTexture(Identifier id) { delegate.sideTexture(id); return this; }
        @Override public ControllerOptions topTexture(Identifier id) { delegate.topTexture(id); return this; }
        @Override public ControllerOptions bottomTexture(Identifier id) { delegate.bottomTexture(id); return this; }
        @Override public ControllerOptions allowVerticalFacing() { delegate.allowVerticalFacing(); return this; }
        @Override public ControllerOptions allowVerticalFacing(boolean enabled) { delegate.allowVerticalFacing(enabled); return this; }
        @Override public ControllerOptions fullyRotationallySymmetric() { delegate.fullyRotationallySymmetric(); return this; }
        @Override public ControllerOptions fullyRotationallySymmetric(boolean enabled) { delegate.fullyRotationallySymmetric(enabled); return this; }
        @Override public ControllerOptions requireVerticalFacing() { delegate.requireVerticalFacing(); return this; }
        @Override public ControllerOptions requireVerticalFacing(boolean enabled) { delegate.requireVerticalFacing(enabled); return this; }
        @Override public ControllerOptions tooltip(String... keys) { delegate.tooltip(keys); return this; }
        @Override public View build() { return new ControllerView(delegate.build()); }
    }

    /** @author howxu <dev@howxu.cn> */
    record ControllerView(ControllerSpec delegate) implements ControllerOptions.View {
        @Override public Identifier id() { return delegate.id(); }
        @Override public Identifier frontTexture() { return delegate.frontTexture(); }
        @Override public Identifier sideTexture() { return delegate.sideTexture(); }
        @Override public Identifier topTexture() { return delegate.topTexture(); }
        @Override public Identifier bottomTexture() { return delegate.bottomTexture(); }
        @Override public boolean allowVerticalFacing() { return delegate.allowVerticalFacing(); }
        @Override public boolean fullyRotationallySymmetric() { return delegate.fullyRotationallySymmetric(); }
        @Override public boolean requireVerticalFacing() { return delegate.requireVerticalFacing(); }
        @Override public List<String> tooltip() { return delegate.tooltip(); }
    }

    /** @author howxu <dev@howxu.cn> */
    static final class Appearance implements AppearanceOptions {
        private final AppearanceSpec.Builder delegate;
        Appearance(AppearanceSpec.Builder delegate) { this.delegate = delegate; }
        @Override public AppearanceOptions appearance(Identifier id) { delegate.appearance(id); return this; }
        @Override public AppearanceOptions appearance(String id) { delegate.appearance(id); return this; }
        @Override public AppearanceOptions machineBasicBlock(Identifier id) { delegate.machineBasicBlock(id); return this; }
        @Override public AppearanceOptions machineBasicBlock(String id) { delegate.machineBasicBlock(id); return this; }
        @Override public AppearanceOptions controllerBaseTexture(Identifier id) { delegate.controllerBaseTexture(id); return this; }
        @Override public AppearanceOptions formedPortBaseTexture(Identifier id) { delegate.formedPortBaseTexture(id); return this; }
        @Override public AppearanceOptions controllerIdleOverlayTexture(Identifier id) { delegate.controllerIdleOverlayTexture(id); return this; }
        @Override public AppearanceOptions controllerActiveOverlayTexture(Identifier id) { delegate.controllerActiveOverlayTexture(id); return this; }
        @Override public View build() { return new AppearanceView(delegate.build()); }
    }

    /** @author howxu <dev@howxu.cn> */
    record AppearanceView(AppearanceSpec delegate) implements AppearanceOptions.View {
        @Override public Identifier machineBasicBlock() { return delegate.machineBasicBlock(); }
        @Override public Identifier controllerBaseTexture() { return delegate.controllerBaseTexture(); }
        @Override public Identifier formedPortBaseTexture() { return delegate.formedPortBaseTexture(); }
        @Override public Identifier controllerIdleOverlayTexture() { return delegate.controllerIdleOverlayTexture(); }
        @Override public Identifier controllerActiveOverlayTexture() { return delegate.controllerActiveOverlayTexture(); }
    }

    /** @author howxu <dev@howxu.cn> */
    static final class Factory implements FactoryOptions {
        private final FactorySpec.Builder delegate;
        Factory(FactorySpec.Builder delegate) { this.delegate = delegate; }
        @Override public FactoryOptions hasFactory(boolean enabled) { delegate.hasFactory(enabled); return this; }
        @Override public FactoryOptions threadLimit(int limit) { delegate.threadLimit(limit); return this; }
        @Override public FactoryOptions thread(String name, Identifier... ids) { delegate.thread(name, ids); return this; }
        @Override public View build() { return new FactoryView(delegate.build()); }
    }

    /** @author howxu <dev@howxu.cn> */
    record FactoryView(FactorySpec delegate) implements FactoryOptions.View {
        @Override public boolean hasFactory() { return delegate.hasFactory(); }
        @Override public int threadLimit() { return delegate.threadLimit(); }
        @Override public List<FactoryOptions.ThreadView> threads() {
            return delegate.threads().stream().<FactoryOptions.ThreadView>map(ThreadView::new).toList();
        }
    }

    /** @author howxu <dev@howxu.cn> */
    private record ThreadView(FactorySpec.ThreadSpec delegate) implements FactoryOptions.ThreadView {
        @Override public String name() { return delegate.name(); }
        @Override public List<Identifier> recipeIds() { return delegate.recipeIds(); }
    }
}
