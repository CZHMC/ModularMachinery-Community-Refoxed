package cn.howxu.mmcr.compat.jade;

import cn.howxu.mmcr.api.recipe.MachineOutput;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes and decodes the list of {@link MachineOutput} the controller exposes to Jade.
 *
 * @author howxu <dev@howxu.cn>
 */
public final class RecipeOutputCodec {
    public static final String OUTPUT_KEY = "mmcr_recipe_output";

    private RecipeOutputCodec() {}

    public static void write(CompoundTag data, List<MachineOutput> outputs) {
        ListTag list = new ListTag();
        for (MachineOutput output : outputs) {
            DataResult<Tag> encoded = MachineOutput.CODEC.encodeStart(NbtOps.INSTANCE, output);
            encoded.result().ifPresent(tag -> {
                if (tag instanceof CompoundTag compound) list.add(compound);
            });
        }
        if (list.isEmpty()) {
            data.remove(OUTPUT_KEY);
            return;
        }
        data.put(OUTPUT_KEY, list);
    }

    public static List<MachineOutput> read(CompoundTag data) {
        if (data == null) return List.of();
        ListTag list = data.getListOrEmpty(OUTPUT_KEY);
        List<MachineOutput> decoded = new ArrayList<>(list.size());
        for (Tag element : list) {
            if (!(element instanceof CompoundTag compound)) continue;
            DataResult<MachineOutput> parsed = MachineOutput.CODEC.parse(NbtOps.INSTANCE, compound);
            parsed.result().ifPresent(decoded::add);
        }
        return List.copyOf(decoded);
    }
}
