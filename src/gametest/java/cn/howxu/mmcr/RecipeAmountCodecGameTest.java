package cn.howxu.mmcr;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;

/**
 * Exercises recipe amount codecs against the live game registry.
 *
 * @author howxu <dev@howxu.cn>
 */
public class RecipeAmountCodecGameTest {
    public void long_recipe_output_amounts_are_capped_at_native_stack_limits(GameTestHelper helper) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        MachineOutput item = MachineOutput.CODEC.parse(ops, output("item", "minecraft:iron_nugget", "count"))
                .getOrThrow();
        MachineOutput fluid = MachineOutput.CODEC.parse(ops, output("fluid", "minecraft:water", "amount"))
                .getOrThrow();

        helper.assertTrue(item instanceof MachineOutput.ItemOutput itemOutput
                        && itemOutput.stack().getCount() == Integer.MAX_VALUE,
                "Long item recipe output amounts are capped at the native item stack limit");
        helper.assertTrue(fluid instanceof MachineOutput.FluidOutput fluidOutput
                        && fluidOutput.stack().getAmount() == Integer.MAX_VALUE,
                "Long fluid recipe output amounts are capped at the native fluid stack limit");
        helper.succeed();
    }

    private static JsonObject output(String type, String id, String amountField) {
        JsonObject stack = new JsonObject();
        stack.addProperty("id", id);
        stack.addProperty(amountField, Long.MAX_VALUE);
        JsonObject output = new JsonObject();
        output.addProperty("type", type);
        output.add("stack", stack);
        return output;
    }
}
