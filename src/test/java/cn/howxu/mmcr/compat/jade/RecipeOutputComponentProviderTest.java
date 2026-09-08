package cn.howxu.mmcr.compat.jade;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import cn.howxu.mmcr.api.recipe.MachineOutputAmount;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.locale.Language;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.TooltipPosition;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeOutputComponentProviderTest {

    @BeforeAll
    static void bootstrap() throws Exception {
        TestBootstrap.bootstrap();
        // Unit tests don't load resource packs, so seed the translation we assert against.
        var ctor = ClientLanguage.class.getDeclaredConstructor(Map.class, boolean.class);
        ctor.setAccessible(true);
        Language.inject(ctor.newInstance(Map.of(
                "jade.mmcr.machine_controller.recipe_output", "Recipe Output"), false));
    }

    @Test
    void rendersLabelForItemOutputs() {
        CompoundTag data = new CompoundTag();
        RecipeOutputCodec.write(data, List.of(new MachineOutputAmount(
                new MachineOutput.ItemOutput(new ItemStack(Items.STONE, 2), 1F), 2L)));
        List<Object> calls = collect(data);
        assertThat(calls.get(0)).isInstanceOf(Component.class);
        assertThat(((Component) calls.get(0)).getString()).contains("Recipe Output");
    }

    @Test
    void emptyOutputsProduceNothing() {
        CompoundTag data = new CompoundTag();
        List<Object> calls = collect(data);
        assertThat(calls).isEmpty();
    }

    @Test
    void priorityIsTooltipTail() {
        assertThat(RecipeOutputComponentProvider.INSTANCE.getDefaultPriority()).isEqualTo(TooltipPosition.TAIL - 9);
    }

    private static List<Object> collect(CompoundTag data) {
        BlockAccessor accessor = (BlockAccessor) Proxy.newProxyInstance(
                BlockAccessor.class.getClassLoader(), new Class<?>[]{BlockAccessor.class},
                (proxy, method, args) -> {
                    if ("getServerData".equals(method.getName())) return data;
                    throw new UnsupportedOperationException(method.getName());
                });
        List<Object> calls = new ArrayList<>();
        ITooltip tooltip = (ITooltip) Proxy.newProxyInstance(
                ITooltip.class.getClassLoader(), new Class<?>[]{ITooltip.class},
                (proxy, method, args) -> {
                    if (args == null || args.length != 1) return null;
                    if (args[0] instanceof Component || args[0] instanceof LayoutElement) {
                        calls.add(args[0]);
                    }
                    return null;
                });
        // JadeUI.smallItem / JadeUI.fluid touch Minecraft.getInstance().font eagerly, which is null
        // under gradle test. The assertions only inspect calls observed before that NPE; swallow it
        // so the recorded label / layout elements can still be asserted.
        try {
            RecipeOutputComponentProvider.INSTANCE.appendTooltip(tooltip, accessor, null);
        } catch (NullPointerException ignored) {
            // downstream rendering failure in unit test context
        }
        return calls;
    }
}
