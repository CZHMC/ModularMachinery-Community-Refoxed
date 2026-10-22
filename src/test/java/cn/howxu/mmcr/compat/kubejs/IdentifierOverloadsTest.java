package cn.howxu.mmcr.compat.kubejs;

import cn.howxu.mmcr.api.controller.ControllerScreenText;
import cn.howxu.mmcr.api.controller.JadeText;
import cn.howxu.mmcr.api.compat.mekanism.ChemicalViewFacet;
import cn.howxu.mmcr.api.capability.status.FailureOccurrence;
import cn.howxu.mmcr.api.capability.status.FailurePhase;
import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.definition.MachineBehaviorContext;
import cn.howxu.mmcr.api.machine.definition.MachineIoView;
import cn.howxu.mmcr.api.recipe.MachineRecipe;
import cn.howxu.mmcr.api.recipe.MachineIngredient;
import cn.howxu.mmcr.test.TestBootstrap;
import dev.latvian.mods.kubejs.util.ID;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Wrapper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Verifies explicit script overloads and their observable declaration effects.
 * @author howxu <dev@howxu.cn>
 */
class IdentifierOverloadsTest {
    @BeforeAll
    static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void exposed_identifier_methods_have_same_name_string_overloads() {
        for (Class<?> type : List.of(KubeJSApi.class, MMCRStartupEventJS.class, MMCRServerEventJS.class,
                RecipeInformationEventJS.class, ControllerScreenTextEventJS.class, MachineBuilderJS.class,
                MachineRecipeBuilderJS.class, MachineLevelBuilderJS.class, MachineStructureBuilderJS.class,
                MachineStructureStageBuilderJS.class, KubeJSInterfaceHelpers.class, ControllerScreenText.class,
                JadeText.class, MachineBehaviorContext.class, MachineIoView.class, MachineRecipe.class,
                ChemicalViewFacet.class, FailureOccurrence.class)) {
            for (var method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                if ((type == MachineRecipe.class || type == FailureOccurrence.class)
                        && Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] arguments = method.getParameterTypes();
                if (Arrays.stream(arguments).noneMatch(t -> t == Identifier.class || t == Identifier[].class)) continue;
                Class<?>[] strings = Arrays.stream(arguments)
                        .map(t -> t == Identifier.class ? String.class : t == Identifier[].class ? String[].class : t)
                        .toArray(Class<?>[]::new);
                assertThatCode(() -> type.getMethod(method.getName(), strings))
                        .as("%s.%s exposes explicit String IDs", type.getSimpleName(), method.getName())
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void string_failure_frames_preserve_nullable_recipe_ids_and_the_original_trace() {
        var original = FailureOccurrence.at(null, Identifier.parse("test:source"), FailurePhase.RECIPE_START,
                Identifier.parse("test:recipe"), 0, Map.of("detail", "kept"));
        var appended = original.append("test:outer", FailurePhase.RUNTIME, "test:outer_recipe", 2)
                .append("test:controller", FailurePhase.RUNTIME, (String) null, null);
        assertThat(original.trace().frames()).hasSize(1);
        assertThat(appended.trace().frames()).extracting(frame -> frame.source().toString())
                .containsExactly("test:source", "test:outer", "test:controller");
        assertThat(appended.trace().frames().get(1).recipeId()).isEqualTo(Identifier.parse("test:outer_recipe"));
        assertThat(appended.trace().frames().getLast().recipeId()).isNull();
        assertThat(appended.details()).containsEntry("detail", "kept");
    }

    @Test
    void rhino_state_preserves_properties_when_identifier_conversion_is_registered() {
        var factory = new ContextFactory();
        factory.getTypeWrappers().registerDirect(Identifier.class, ID::mc);
        var context = factory.enter();
        var scope = context.initStandardObjects();
        ScriptableObject.putProperty(scope, "api", new KubeJSApi(), context);

        Object result = context.evaluateString(scope, """
                api.state('minecraft:iron_bars[east=true,north=false,south=true,waterlogged=false,west=true]')
                """, "state-identifier-conversion-test", 1, null);

        var predicate = (BlockPredicate.OfBlockState) Wrapper.unwrapped(result);
        var expected = Blocks.IRON_BARS.defaultBlockState()
                .setValue(IronBarsBlock.EAST, true)
                .setValue(IronBarsBlock.NORTH, false)
                .setValue(IronBarsBlock.SOUTH, true)
                .setValue(IronBarsBlock.WEST, true)
                .setValue(IronBarsBlock.WATERLOGGED, false);
        assertThat(predicate.state()).isEqualTo(expected);
        assertThat(predicate.matches(expected)).isTrue();
        assertThat(predicate.matches(expected.setValue(IronBarsBlock.EAST, false))).isFalse();
    }

    @Test
    void helper_identifier_overloads_resolve_the_same_registered_resources() {
        var api = new KubeJSApi();
        assertThat(((BlockPredicate.OfBlockState) api.state(Identifier.parse("minecraft:stone")))
                .state()).isSameAs(Blocks.STONE.defaultBlockState());
        assertThat(((BlockPredicate.OfBlock) api.block(Identifier.parse("minecraft:stone")))
                .block()).isSameAs(Blocks.STONE);
        assertThat(((BlockPredicate.OfTag) api.tag(Identifier.parse("minecraft:logs")))
                .tag().location()).isEqualTo(Identifier.parse("minecraft:logs"));
        var input = (MachineIngredient.ItemIngredient)
                api.itemInput(Identifier.parse("minecraft:iron_ingot"), 2, 1F);
        assertThat(input.item().test(Items.IRON_INGOT.getDefaultInstance())).isTrue();
        assertThat(input.item().test(Items.GOLD_INGOT.getDefaultInstance())).isFalse();
        assertThat(api.fluidStack(Identifier.parse("minecraft:water"), 10).getFluid()).isSameAs(Fluids.WATER);
        var builder = new MachineRecipeBuilderJS("test:identifier_recipe")
                .id(Identifier.parse("test:renamed_recipe"))
                .itemInput(Identifier.parse("minecraft:iron_ingot"), 2)
                .itemOutput("minecraft:gold_ingot", 1)
                .requiredHosts(Identifier.parse("test:host"), Identifier.parse("test:other_host"));
        builder.recipePoolId = Identifier.parse("test:pool");
        var recipe = builder.createObject();
        assertThat(recipe.id()).isEqualTo(Identifier.parse("test:renamed_recipe"));
        assertThat(recipe.canRunOnConnectedHost("test:host")).isTrue();
        assertThat(recipe.canRunOnConnectedHost("test:unlisted")).isFalse();
        assertThat(recipe.withId("test:copied").id()).isEqualTo(Identifier.parse("test:copied"));
    }
}
