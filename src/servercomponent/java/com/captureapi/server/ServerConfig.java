package com.captureapi.server;

import net.minecraftforge.common.ForgeConfigSpec;

/** 服务端组件配置（T9.1/T9.3）。 */
public final class ServerConfig {

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue DEFAULT_PERMISSION;
    private static final ForgeConfigSpec.ConfigValue<String> PERMISSION_FILE;
    private static final ForgeConfigSpec.IntValue MAX_CHUNKS_PER_PLAYER;
    private static final ForgeConfigSpec.IntValue MAX_CHUNKS_GLOBAL;
    private static final ForgeConfigSpec.IntValue MAX_REQUESTS_PER_SECOND;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("CaptureAPI 服务端组件配置（M9）").push("captureapi_server");
        DEFAULT_PERMISSION = builder.comment("未列玩家默认是否允许申请").define("default_permission", true);
        PERMISSION_FILE = builder.comment("权限文件（相对游戏目录）")
                .define("permission_file", "config/captureapi/permissions.json");
        MAX_CHUNKS_PER_PLAYER = builder.comment("单玩家临时 ticket 上限")
                .defineInRange("max_chunks_per_player", 4096, 16, 65536);
        MAX_CHUNKS_GLOBAL = builder.comment("全局临时 ticket 上限")
                .defineInRange("max_chunks_global", 16384, 64, 262144);
        MAX_REQUESTS_PER_SECOND = builder.comment("单玩家申请频率上限（次/秒）")
                .defineInRange("max_requests_per_second", 20, 1, 200);
        builder.pop();
        SPEC = builder.build();
    }

    private ServerConfig() {
        throw new AssertionError("no instances");
    }

    public static boolean defaultPermission() {
        return DEFAULT_PERMISSION.get();
    }

    public static String permissionFile() {
        return PERMISSION_FILE.get();
    }

    public static int maxChunksPerPlayer() {
        return MAX_CHUNKS_PER_PLAYER.get();
    }

    public static int maxChunksGlobal() {
        return MAX_CHUNKS_GLOBAL.get();
    }

    public static int maxRequestsPerSecond() {
        return MAX_REQUESTS_PER_SECOND.get();
    }
}
