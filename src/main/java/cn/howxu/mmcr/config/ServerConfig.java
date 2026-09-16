package cn.howxu.mmcr.config;

import cn.howxu.mmcr.internal.assembly.MultiblockAssemblyService;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Defines server-authoritative MMCR configuration values.
 * @author howxu <dev@howxu.cn>
 */
public final class ServerConfig {
    public static final int DEFAULT_MACHINE_CHECK_INTERVAL_TICKS = 40;
    public static final int DEFAULT_TERMINAL_MAX_DEMOLISH_BLOCKS = MultiblockAssemblyService.MAX_BLOCKS_PER_OPERATION;
    public static final int DEFAULT_BUILD_BLOCKS_PER_TICK = 256;
    public static final int DEFAULT_BUILD_TASK_TIMEOUT_TICKS = 20 * 60;
    public static final int DEFAULT_STRUCTURE_SCAN_BATCHES = 10;
    public static final int DEFAULT_STRUCTURE_SENTINEL_COUNT = 16;
    public static final int DEFAULT_STRUCTURE_SYNC_MAX_BLOCKS = 524_288;
    public static final int DEFAULT_MAX_REQUESTS_PER_TICK = 1024;
    public static final ModConfigSpec.IntValue MACHINE_CHECK_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue TERMINAL_MAX_DEMOLISH_BLOCKS;
    public static final ModConfigSpec.IntValue BUILD_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue BUILD_TASK_TIMEOUT_TICKS;
    public static final ModConfigSpec.IntValue STRUCTURE_SCAN_BATCHES;
    public static final ModConfigSpec.IntValue STRUCTURE_SENTINEL_COUNT;
    public static final ModConfigSpec.BooleanValue STRUCTURE_SENTINEL_ENABLED;
    public static final ModConfigSpec.IntValue STRUCTURE_SYNC_MAX_BLOCKS;
    public static final ModConfigSpec.DoubleValue ENERGY_CONSUMPTION_MULTIPLIER;
    public static final ModConfigSpec.IntValue MAX_REQUESTS_PER_TICK;
    public static final ModConfigSpec SPEC;

    static {
        var builder = new ModConfigSpec.Builder();

        builder.push("terminal");
        TERMINAL_MAX_DEMOLISH_BLOCKS = builder
                .comment("Maximum blocks removed by one terminal demolish operation")
                .defineInRange("max_demolish_blocks", DEFAULT_TERMINAL_MAX_DEMOLISH_BLOCKS, 1, 1_000_000);
        builder.pop();

        builder.push("build");
        BUILD_BLOCKS_PER_TICK = builder
                .comment("Maximum structure blocks placed by one controller per tick")
                .defineInRange("blocks_per_tick", DEFAULT_BUILD_BLOCKS_PER_TICK, 1, 1_000_000);
        BUILD_TASK_TIMEOUT_TICKS = builder
                .comment("Maximum age of a pending structure build task")
                .defineInRange("task_timeout_ticks", DEFAULT_BUILD_TASK_TIMEOUT_TICKS, 1, 1_000_000);
        builder.pop();

        builder.push("structure");
        MACHINE_CHECK_INTERVAL_TICKS = builder
                .comment("Ticks between controller structure-check passes")
                .defineInRange("check_interval_ticks", DEFAULT_MACHINE_CHECK_INTERVAL_TICKS, 1, 600);
        STRUCTURE_SCAN_BATCHES = builder
                .comment("Number of batches used to scan a structure across server ticks")
                .defineInRange("scan_batches", DEFAULT_STRUCTURE_SCAN_BATCHES, 1, 32);
        STRUCTURE_SENTINEL_COUNT = builder
                .comment("Number of deterministic structure entries checked before each scan batch")
                .defineInRange("sentinel_count", DEFAULT_STRUCTURE_SENTINEL_COUNT, 0, 128);
        STRUCTURE_SENTINEL_ENABLED = builder
                .comment("Whether deterministic structure sentinel checks are enabled")
                .define("sentinel_enabled", true);
        builder.pop();

        builder.push("energy");
        ENERGY_CONSUMPTION_MULTIPLIER = builder
                .comment("Global multiplier on energy consumption")
                .defineInRange("consumption_multiplier", 1.0, 0.0, 100.0);
        builder.pop();

        builder.push("network");
        MAX_REQUESTS_PER_TICK = builder
                .comment("Maximum machine network requests processed per server tick")
                .defineInRange("max_requests_per_tick", DEFAULT_MAX_REQUESTS_PER_TICK, 1, 1_000_000);
        STRUCTURE_SYNC_MAX_BLOCKS = builder
                .comment("Maximum block pattern entries synchronized for one machine structure")
                .defineInRange("sync_max_blocks", DEFAULT_STRUCTURE_SYNC_MAX_BLOCKS, 1, Integer.MAX_VALUE);
        builder.pop();

        SPEC = builder.build();
    }

    private ServerConfig() {
    }
}
