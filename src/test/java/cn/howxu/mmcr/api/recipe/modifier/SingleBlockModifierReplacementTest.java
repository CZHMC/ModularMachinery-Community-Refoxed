package cn.howxu.mmcr.api.recipe.modifier;

import cn.howxu.mmcr.api.machine.BlockPredicate;
import cn.howxu.mmcr.api.machine.modifier.MachineModifier;
import cn.howxu.mmcr.test.TestBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author howxu <dev@howxu.cn>
 */
class SingleBlockModifierReplacementTest {
    @BeforeAll static void bootstrapMinecraft() throws Exception {
        TestBootstrap.bootstrap();
    }

    @Test
    void stores_predicate_name_modifiers_and_descriptive_stack() {
        MachineModifier modifier = MachineModifier.numeric("duration", "input", 2D, "add", false);
        ItemStack stack = new ItemStack(Blocks.GOLD_BLOCK);
        var replacement = new SingleBlockModifierReplacement(
                "speed", new BlockPredicate.OfBlock(Blocks.GOLD_BLOCK), List.of(modifier), stack);

        assertThat(replacement.getModifierName()).isEqualTo("speed");
        assertThat(replacement.getReplacement()).isEqualTo(new BlockPredicate.OfBlock(Blocks.GOLD_BLOCK));
        assertThat(replacement.getModifiers()).containsExactly(modifier);
        assertThat(replacement.getDescriptiveStack()).isSameAs(stack);
    }

    @Test
    void constructor_requires_only_value_state_without_position_or_description() {
        var replacement = new SingleBlockModifierReplacement(
                "speed", new BlockPredicate.Any(), List.of(), ItemStack.EMPTY);

        assertThat(replacement.getModifierName()).isEqualTo("speed");
        assertThat(replacement.getReplacement()).isEqualTo(new BlockPredicate.Any());
        assertThat(replacement.getDescriptionLines()).isEmpty();
    }

    @Test
    void modifier_list_is_not_mutable_through_constructor_input() {
        List<MachineModifier> modifiers = new ArrayList<>();
        var replacement = new SingleBlockModifierReplacement(
                "speed", new BlockPredicate.Any(), modifiers, ItemStack.EMPTY);
        modifiers.add(MachineModifier.numeric("duration", "input", 1D, "add", false));

        assertThat(replacement.getModifiers()).isEmpty();
        assertThatThrownBy(() -> replacement.getModifiers().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void replacements_with_same_values_compare_equal() {
        MachineModifier modifier = MachineModifier.numeric("output", "output", 2D, "multiply", false);
        var first = new SingleBlockModifierReplacement(
                "speed", new BlockPredicate.OfBlock(Blocks.GOLD_BLOCK), List.of(modifier), new ItemStack(Blocks.GOLD_BLOCK));
        var second = new SingleBlockModifierReplacement(
                "speed", new BlockPredicate.OfBlock(Blocks.GOLD_BLOCK), List.of(modifier), new ItemStack(Blocks.GOLD_BLOCK));

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSameHashCodeAs(second);
    }

    @Test
    void descriptive_stack_count_participates_in_equals_and_hash_code() {
        var first = new SingleBlockModifierReplacement(
                "speed", new BlockPredicate.OfBlock(Blocks.GOLD_BLOCK), List.of(), new ItemStack(Blocks.GOLD_BLOCK, 1));
        var second = new SingleBlockModifierReplacement(
                "speed", new BlockPredicate.OfBlock(Blocks.GOLD_BLOCK), List.of(), new ItemStack(Blocks.GOLD_BLOCK, 64));

        assertThat(first).isNotEqualTo(second);
        assertThat(first.hashCode()).isNotEqualTo(second.hashCode());
    }
}
