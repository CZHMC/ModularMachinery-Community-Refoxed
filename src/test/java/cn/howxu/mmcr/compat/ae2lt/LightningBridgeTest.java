package cn.howxu.mmcr.compat.ae2lt;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.OutputRegistry;
import cn.howxu.mmcr.api.recipe.requirement.MachineRequirement;
import cn.howxu.mmcr.api.recipe.requirement.RequirementHandlerRegistry;
import cn.howxu.mmcr.test.TestBootstrap;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises real filtered classpaths instead of merely checking an availability flag.
 * @author howxu <dev@howxu.cn>
 */
class LightningBridgeTest {
    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrapCapabilities();
    }

    @Test
    void unavailableBridgeDoesNotLinkNativeClasses() throws Exception {
        checkFilteredClasspath(false);
        checkFilteredClasspath(true);
    }

    private static void checkFilteredClasspath(boolean ae2Present) throws Exception {
        AtomicInteger nativeLoadAttempts = new AtomicInteger();
        ClassLoader isolated = new ClassLoader(LightningBridgeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.moakiee.ae2lt.")
                        || name.startsWith("cn.howxu.mmcr.compat.ae2lt.loaded.")
                        || name.startsWith("cn.howxu.mmcr.compat.ae2lt.client.")
                        || !ae2Present && (name.startsWith("appeng.")
                        || name.startsWith("cn.howxu.mmcr.compat.appliedenergistics2.loaded."))) {
                    nativeLoadAttempts.incrementAndGet();
                    throw new ClassNotFoundException(name);
                }
                if (name.startsWith("cn.howxu.mmcr.compat.ae2lt.")
                        || name.startsWith("cn.howxu.mmcr.api.compat.ae2lt.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            try (var stream = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                                if (stream == null) throw new ClassNotFoundException(name);
                                byte[] bytes = stream.readAllBytes();
                                loaded = defineClass(name, bytes, 0, bytes.length);
                            } catch (IOException exception) {
                                throw new ClassNotFoundException(name, exception);
                            }
                        }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };

        try (var requirements = RequirementHandlerRegistry.openTestScope();
             var outputs = OutputRegistry.openTestScope()) {
            Class<?> types = Class.forName("cn.howxu.mmcr.compat.ae2lt.LightningRecipeTypes", true, isolated);
            types.getMethod("register").invoke(null);
            Class<?> bridgeType = Class.forName("cn.howxu.mmcr.compat.ae2lt.LightningBridge", true, isolated);
            Class<?> bootstrap = Class.forName("cn.howxu.mmcr.compat.ae2lt.LightningBridgeBootstrap", true, isolated);
            Object bridge = bootstrap.getMethod("selectForTesting", boolean.class, boolean.class)
                    .invoke(null, ae2Present, false);
            assertThat(bridgeType.getMethod("available").invoke(bridge)).isEqualTo(false);
            bridgeType.getMethod("registerCapabilityDefinitions").invoke(bridge);
            assertThat(bridgeType.getMethod("resourceFamilies").invoke(bridge)).isEqualTo(List.of());
            assertThat(bridgeType.getMethod("available").invoke(bridgeType.getMethod("get").invoke(null))).isEqualTo(false);

            for (String tier : List.of("high_voltage", "extreme_high_voltage")) {
                JsonObject payload = new JsonObject();
                payload.addProperty("type", "ae2lt:" + tier);
                payload.addProperty("amount", 123L);
                var requirement = MachineRequirement.CODEC.parse(JsonOps.INSTANCE, payload).getOrThrow();
                assertThat(requirement.getClass().getClassLoader()).isSameAs(isolated);
                assertThat(MachineRequirement.CODEC.encodeStart(JsonOps.INSTANCE, requirement).getOrThrow()
                        .getAsJsonObject().get("amount").getAsLong()).isEqualTo(123L);
                var output = MachineOutput.CODEC.parse(JsonOps.INSTANCE, payload).getOrThrow();
                assertThat(output.getClass().getClassLoader()).isSameAs(isolated);
                assertThat(OutputRegistry.toRequirement(output, List.of()).type()).isSameAs(requirement.type());
            }
        }
        assertThat(nativeLoadAttempts).hasValue(0);
    }
}
