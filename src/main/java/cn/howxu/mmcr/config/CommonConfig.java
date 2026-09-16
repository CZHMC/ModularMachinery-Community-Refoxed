package cn.howxu.mmcr.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Defines MMCR configuration values shared by both physical sides.
 * @author howxu <dev@howxu.cn>
 */
public final class CommonConfig {
    public static final int DEFAULT_PREVIEW_MAX_ENTRIES = 131_072;
    public static final int DEFAULT_PREVIEW_DURATION_TICKS = 200;
    public static final int DEFAULT_PORT_STORAGE_MAX_ENTRIES = 1_024;
    public static final int DEFAULT_PORT_STORAGE_MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int DEFAULT_DATA_VALUE_MAX_ENTRIES = 1_024;
    public static final int DEFAULT_DATA_VALUE_MAX_DEPTH = 16;
    public static final int DEFAULT_MAX_STRING_LENGTH = 256;
    public static final int DEFAULT_SCREEN_TEXT_MAX_LINES = 1_024;
    public static final int DEFAULT_SCREEN_TEXT_MAX_ENCODED_BYTES = 65_536;
    public static final int DEFAULT_RUNTIME_CONTENT_MAX_STRUCTURES = 4_096;
    public static final int DEFAULT_RUNTIME_CONTENT_MAX_RECIPES = 16_384;
    public static final int DEFAULT_RUNTIME_CONTENT_MAX_SPECS = 4_096;
    public static final int DEFAULT_RUNTIME_CONTENT_MAX_TOOLTIP_LINES = 1_024;
    public static final int DEFAULT_MACHINE_STATE_MAX_LEVEL_SNAPSHOTS = 1_024;
    public static final int DEFAULT_MACHINE_STATE_MAX_INSTALLED_MODULES = 1_024;
    public static final int DEFAULT_FACTORY_STATE_MAX_THREAD_SNAPSHOTS = 1_024;
    public static final int DEFAULT_FACTORY_STATE_MAX_LANE_SNAPSHOTS = 1_024;
    public static final int DEFAULT_FACTORY_STATE_MAX_LEVEL_SNAPSHOTS = 1_024;
    public static final int DEFAULT_FAILURE_MAX_DETAILS = 64;
    public static final int DEFAULT_TERMINAL_STATE_MAX_PREVIEW_LAYERS = 128;
    public static final int DEFAULT_STRUCTURE_SYNC_MAX_COLLECTION_ENTRIES = 1_024;
    public static final int DEFAULT_STRUCTURE_SYNC_MAX_SYMBOL_REQUIREMENTS = 256;
    public static final int DEFAULT_RECIPE_SYNC_MAX_REQUIREMENTS = 4_096;
    public static final int DEFAULT_RECIPE_SYNC_MAX_OUTPUTS = 4_096;
    public static final ModConfigSpec.IntValue PREVIEW_MAX_ENTRIES;
    public static final ModConfigSpec.IntValue PREVIEW_DURATION_TICKS;
    public static final ModConfigSpec.IntValue PORT_STORAGE_MAX_ENTRIES;
    public static final ModConfigSpec.IntValue PORT_STORAGE_MAX_PAYLOAD_BYTES;
    public static final ModConfigSpec.IntValue DATA_VALUE_MAX_ENTRIES;
    public static final ModConfigSpec.IntValue DATA_VALUE_MAX_DEPTH;
    public static final ModConfigSpec.IntValue MAX_STRING_LENGTH;
    public static final ModConfigSpec.IntValue SCREEN_TEXT_MAX_LINES;
    public static final ModConfigSpec.IntValue SCREEN_TEXT_MAX_ENCODED_BYTES;
    public static final ModConfigSpec.IntValue RUNTIME_CONTENT_MAX_STRUCTURES;
    public static final ModConfigSpec.IntValue RUNTIME_CONTENT_MAX_RECIPES;
    public static final ModConfigSpec.IntValue RUNTIME_CONTENT_MAX_SPECS;
    public static final ModConfigSpec.IntValue RUNTIME_CONTENT_MAX_TOOLTIP_LINES;
    public static final ModConfigSpec.IntValue MACHINE_STATE_MAX_LEVEL_SNAPSHOTS;
    public static final ModConfigSpec.IntValue MACHINE_STATE_MAX_INSTALLED_MODULES;
    public static final ModConfigSpec.IntValue FACTORY_STATE_MAX_THREAD_SNAPSHOTS;
    public static final ModConfigSpec.IntValue FACTORY_STATE_MAX_LANE_SNAPSHOTS;
    public static final ModConfigSpec.IntValue FACTORY_STATE_MAX_LEVEL_SNAPSHOTS;
    public static final ModConfigSpec.IntValue FAILURE_MAX_DETAILS;
    public static final ModConfigSpec.IntValue TERMINAL_STATE_MAX_PREVIEW_LAYERS;
    public static final ModConfigSpec.IntValue STRUCTURE_SYNC_MAX_COLLECTION_ENTRIES;
    public static final ModConfigSpec.IntValue STRUCTURE_SYNC_MAX_SYMBOL_REQUIREMENTS;
    public static final ModConfigSpec.IntValue RECIPE_SYNC_MAX_REQUIREMENTS;
    public static final ModConfigSpec.IntValue RECIPE_SYNC_MAX_OUTPUTS;
    public static final ModConfigSpec.IntValue RECIPE_SYNC_MAX_MODIFIERS;
    public static final ModConfigSpec.IntValue RECIPE_SYNC_MAX_REQUIRED_HOSTS;
    public static final ModConfigSpec SPEC;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("network");
        builder.push("preview");
        PREVIEW_MAX_ENTRIES = define(builder, "max_entries", DEFAULT_PREVIEW_MAX_ENTRIES,
                "Maximum multiblock preview entries; client and server values must match");
        PREVIEW_DURATION_TICKS = define(builder, "duration_ticks", DEFAULT_PREVIEW_DURATION_TICKS,
                "Default multiblock preview duration in ticks");
        builder.pop();
        builder.push("port_storage");
        PORT_STORAGE_MAX_ENTRIES = define(builder, "max_entries", DEFAULT_PORT_STORAGE_MAX_ENTRIES,
                "Maximum port storage entries per payload; client and server values must match");
        PORT_STORAGE_MAX_PAYLOAD_BYTES = define(builder, "max_payload_bytes", DEFAULT_PORT_STORAGE_MAX_PAYLOAD_BYTES,
                "Maximum encoded port storage payload size; client and server values must match");
        builder.pop();
        builder.push("data_value");
        DATA_VALUE_MAX_ENTRIES = define(builder, "max_entries", DEFAULT_DATA_VALUE_MAX_ENTRIES,
                "Maximum entries in one serialized data value collection");
        DATA_VALUE_MAX_DEPTH = define(builder, "max_depth", DEFAULT_DATA_VALUE_MAX_DEPTH,
                "Maximum nesting depth in one serialized data value");
        MAX_STRING_LENGTH = define(builder, "max_string_length", DEFAULT_MAX_STRING_LENGTH,
                "Maximum UTF-8 string length in data and machine state payloads");
        builder.pop();
        builder.push("screen_text");
        SCREEN_TEXT_MAX_LINES = define(builder, "max_lines", DEFAULT_SCREEN_TEXT_MAX_LINES,
                "Maximum controller screen text lines per payload");
        SCREEN_TEXT_MAX_ENCODED_BYTES = define(builder, "max_encoded_bytes", DEFAULT_SCREEN_TEXT_MAX_ENCODED_BYTES,
                "Maximum encoded controller screen text size");
        builder.pop();
        builder.push("runtime_content");
        RUNTIME_CONTENT_MAX_STRUCTURES = define(builder, "max_structures", DEFAULT_RUNTIME_CONTENT_MAX_STRUCTURES,
                "Maximum synchronized machine structures");
        RUNTIME_CONTENT_MAX_RECIPES = define(builder, "max_recipes", DEFAULT_RUNTIME_CONTENT_MAX_RECIPES,
                "Maximum synchronized machine recipes");
        RUNTIME_CONTENT_MAX_SPECS = define(builder, "max_specs", DEFAULT_RUNTIME_CONTENT_MAX_SPECS,
                "Maximum synchronized controller or appearance specs");
        RUNTIME_CONTENT_MAX_TOOLTIP_LINES = define(builder, "max_tooltip_lines", DEFAULT_RUNTIME_CONTENT_MAX_TOOLTIP_LINES,
                "Maximum synchronized tooltip lines");
        builder.pop();
        builder.push("machine_state");
        MACHINE_STATE_MAX_LEVEL_SNAPSHOTS = define(builder, "max_level_snapshots", DEFAULT_MACHINE_STATE_MAX_LEVEL_SNAPSHOTS,
                "Maximum machine level snapshots per payload");
        MACHINE_STATE_MAX_INSTALLED_MODULES = define(builder, "max_installed_modules", DEFAULT_MACHINE_STATE_MAX_INSTALLED_MODULES,
                "Maximum installed modules in one machine state payload");
        builder.pop();
        builder.push("factory_state");
        FACTORY_STATE_MAX_THREAD_SNAPSHOTS = define(builder, "max_thread_snapshots", DEFAULT_FACTORY_STATE_MAX_THREAD_SNAPSHOTS,
                "Maximum factory thread snapshots per payload");
        FACTORY_STATE_MAX_LANE_SNAPSHOTS = define(builder, "max_lane_snapshots", DEFAULT_FACTORY_STATE_MAX_LANE_SNAPSHOTS,
                "Maximum factory lane snapshots per payload");
        FACTORY_STATE_MAX_LEVEL_SNAPSHOTS = define(builder, "max_level_snapshots", DEFAULT_FACTORY_STATE_MAX_LEVEL_SNAPSHOTS,
                "Maximum factory level snapshots per payload");
        builder.pop();
        builder.push("failure");
        FAILURE_MAX_DETAILS = define(builder, "max_details", DEFAULT_FAILURE_MAX_DETAILS,
                "Maximum failure details in machine state payloads");
        builder.pop();
        builder.push("terminal_state");
        TERMINAL_STATE_MAX_PREVIEW_LAYERS = define(builder, "max_preview_layers", DEFAULT_TERMINAL_STATE_MAX_PREVIEW_LAYERS,
                "Maximum preview layers in terminal state payloads");
        builder.pop();
        builder.push("structure_sync");
        STRUCTURE_SYNC_MAX_COLLECTION_ENTRIES = define(builder, "max_collection_entries",
                DEFAULT_STRUCTURE_SYNC_MAX_COLLECTION_ENTRIES, "Maximum entries in synchronized structure collections");
        STRUCTURE_SYNC_MAX_SYMBOL_REQUIREMENTS = define(builder, "max_symbol_requirements",
                DEFAULT_STRUCTURE_SYNC_MAX_SYMBOL_REQUIREMENTS, "Maximum symbol requirements in synchronized structures");
        builder.pop();
        builder.push("recipe_sync");
        RECIPE_SYNC_MAX_REQUIREMENTS = define(builder, "max_requirements", DEFAULT_RECIPE_SYNC_MAX_REQUIREMENTS,
                "Maximum requirements in one synchronized recipe");
        RECIPE_SYNC_MAX_OUTPUTS = define(builder, "max_outputs", DEFAULT_RECIPE_SYNC_MAX_OUTPUTS,
                "Maximum outputs in one synchronized recipe");
        RECIPE_SYNC_MAX_MODIFIERS = define(builder, "max_modifiers", DEFAULT_STRUCTURE_SYNC_MAX_COLLECTION_ENTRIES,
                "Maximum modifiers in one synchronized recipe");
        RECIPE_SYNC_MAX_REQUIRED_HOSTS = define(builder, "max_required_hosts", DEFAULT_STRUCTURE_SYNC_MAX_COLLECTION_ENTRIES,
                "Maximum required hosts in one synchronized recipe");
        builder.pop();
        builder.pop();
        SPEC = builder.build();
    }

    private CommonConfig() {
    }

    private static ModConfigSpec.IntValue define(ModConfigSpec.Builder builder, String key, int defaultValue, String comment) {
        return builder.comment(comment).defineInRange(key, defaultValue, 1, Integer.MAX_VALUE);
    }

    public static int valueOrDefault(ModConfigSpec.IntValue value, int defaultValue) {
        try {
            return value.get();
        } catch (IllegalStateException ignored) {
            return defaultValue;
        }
    }
}
