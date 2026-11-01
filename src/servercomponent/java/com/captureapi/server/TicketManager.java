package com.captureapi.server;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * 服务端临时区块 ticket 受理（T9.2）。
 *
 * <p>每玩家持有自己的 ChunkPos 集合；请求去重、单玩家/全局限额、断线/释放/停服回收。
 * ticket 级别取“类玩家级”（ENTITY_TICKING），ticket 主体为玩家 UUID，便于按玩家回收。</p>
 */
public final class TicketManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CaptureAPI-Server");

    /** 自定义 ticket 类型（仅在服务端内存中使用，不持久化）。 */
    private static final TicketType<UUID> CAPTURE_TICKET = TicketType.create("captureapi_camera",
            Comparator.comparing(UUID::toString));
    private static final int TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    private static final int MAX_CHUNKS_PER_PACKET = 2048;

    private final Map<UUID, Set<Long>> owned = new HashMap<>();
    private final Map<UUID, long[]> rateWindows = new HashMap<>();
    private int total;

    /** 受理结果。 */
    public record Result(boolean ok, String code, int accepted) {
    }

    /**
     * 受理区块申请。
     *
     * @param player 玩家
     * @param chunks ChunkPos long 集合
     * @return 结果
     */
    public synchronized Result request(ServerPlayer player, long[] chunks) {
        if (chunks == null || chunks.length == 0) {
            return new Result(false, "E_EMPTY_REQUEST", 0);
        }
        if (chunks.length > MAX_CHUNKS_PER_PACKET) {
            return new Result(false, "E_PACKET_LIMIT", 0);
        }
        if (!rateAllowed(player.getUUID())) {
            return new Result(false, "E_RATE_LIMIT", 0);
        }
        Set<Long> current = owned.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>());
        Set<Long> incoming = new HashSet<>();
        for (long chunk : chunks) {
            if (!current.contains(chunk)) {
                incoming.add(chunk);
            }
        }
        if (incoming.isEmpty()) {
            return new Result(true, "OK", 0);
        }
        if (current.size() + incoming.size() > ServerConfig.maxChunksPerPlayer()) {
            return new Result(false, "E_PLAYER_LIMIT", 0);
        }
        if (total + incoming.size() > ServerConfig.maxChunksGlobal()) {
            return new Result(false, "E_GLOBAL_LIMIT", 0);
        }
        ServerLevel level = player.serverLevel();
        for (long encoded : incoming) {
            level.getChunkSource().addRegionTicket(CAPTURE_TICKET, decode(encoded), TICKET_LEVEL,
                    player.getUUID());
            current.add(encoded);
            total++;
        }
        LOGGER.info("[TICKETS] request player={} accepted={} owned={} total={}", player.getUUID(),
                incoming.size(), current.size(), total);
        return new Result(true, "OK", incoming.size());
    }

    /**
     * 释放区块。
     *
     * @param player 玩家
     * @param all    是否释放全部
     * @param chunks 指定区块（all=false 时使用）
     * @return 结果
     */
    public synchronized Result release(ServerPlayer player, boolean all, long[] chunks) {
        Set<Long> current = owned.get(player.getUUID());
        if (current == null || current.isEmpty()) {
            return new Result(true, "OK", 0);
        }
        if (all) {
            releaseAllInternal(player);
            return new Result(true, "OK", 0);
        }
        if (chunks == null) {
            return new Result(true, "OK", 0);
        }
        ServerLevel level = player.serverLevel();
        int removed = 0;
        for (long encoded : chunks) {
            if (current.remove(encoded)) {
                level.getChunkSource().removeRegionTicket(CAPTURE_TICKET, decode(encoded), TICKET_LEVEL,
                        player.getUUID());
                total--;
                removed++;
            }
        }
        if (current.isEmpty()) {
            owned.remove(player.getUUID());
        }
        LOGGER.info("[TICKETS] release player={} removed={} owned={} total={}", player.getUUID(),
                removed, current.size(), total);
        return new Result(true, "OK", removed);
    }

    /** 玩家断线/退出时回收全部 ticket。 */
    public synchronized void releaseAll(ServerPlayer player) {
        releaseAllInternal(player);
        rateWindows.remove(player.getUUID());
    }

    private void releaseAllInternal(ServerPlayer player) {
        Set<Long> current = owned.remove(player.getUUID());
        if (current == null || current.isEmpty()) {
            LOGGER.info("[TICKETS] releaseAll player={} released=0 total={}", player.getUUID(), total);
            return;
        }
        ServerLevel level = player.serverLevel();
        int released = 0;
        for (long encoded : current) {
            level.getChunkSource().removeRegionTicket(CAPTURE_TICKET, decode(encoded), TICKET_LEVEL,
                    player.getUUID());
            total--;
            released++;
        }
        LOGGER.info("[TICKETS] releaseAll player={} released={} total={}", player.getUUID(), released, total);
    }

    /** 服务端停止清空（ticket 随世界关闭自然消失，内存计数归零）。 */
    public synchronized void clear() {
        LOGGER.info("[TICKETS] clear totalBefore={}", total);
        owned.clear();
        rateWindows.clear();
        total = 0;
    }

    public synchronized int totalTickets() {
        return total;
    }

    private boolean rateAllowed(UUID uuid) {
        long now = System.nanoTime();
        long[] window = rateWindows.computeIfAbsent(uuid, ignored -> new long[] {now, 0L});
        if (now - window[0] >= 1_000_000_000L) {
            window[0] = now;
            window[1] = 0L;
        }
        if (window[1] >= ServerConfig.maxRequestsPerSecond()) {
            return false;
        }
        window[1]++;
        return true;
    }

    private static ChunkPos decode(long encoded) {
        return new ChunkPos(ChunkPos.getX(encoded), ChunkPos.getZ(encoded));
    }
}
