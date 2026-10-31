package com.captureapi.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;

/** 服务端权限文件（T9.5）：UUID→boolean，缺省取 default_permission。 */
public final class Permissions {

    private static final Permissions INSTANCE = new Permissions();
    private final Map<UUID, Boolean> overrides = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    private Permissions() {
    }

    public static Permissions get() {
        return INSTANCE;
    }

    /** 重载权限文件（文件不存在则允许默认值）。 */
    public synchronized void reload() {
        overrides.clear();
        loaded = true;
        String configured = ServerConfig.permissionFile();
        Path path = FMLPaths.GAMEDIR.get().resolve(configured);
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
                try {
                    overrides.put(UUID.fromString(entry.getKey()), entry.getValue().getAsBoolean());
                } catch (RuntimeException ignored) {
                    // 跳过非法条目
                }
            }
        } catch (Throwable ignored) {
            // 权限文件损坏：保守使用默认值，不阻断服务器
        }
    }

    /**
     * @param player 服务端玩家
     * @return 是否允许相机区块申请（OP 恒允许）
     */
    public boolean isAllowed(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (!loaded) {
            reload();
        }
        if (player.hasPermissions(2)) {
            return true;
        }
        return overrides.getOrDefault(player.getUUID(), ServerConfig.defaultPermission());
    }
}
