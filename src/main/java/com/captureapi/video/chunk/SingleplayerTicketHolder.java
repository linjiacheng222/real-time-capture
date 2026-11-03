package com.captureapi.video.chunk;

import com.captureapi.core.constant.CaptureConstants;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单机相机区块 ticket 持有者（缺陷1）。
 *
 * <p>单机集成服没有 M9 远程组件，由本类在 {@link CaptureChunkPlanner} 求得目标区块后，
 * 经集成服线程 {@code addRegionTicket/removeRegionTicket} 强加载；客户端渲染线程只做 diff 与投递。</p>
 *
 * <p>ticket 级别参照服务端组件 TicketManager：ENTITY_TICKING（类玩家级），
 * 自定义类型 {@code captureapi_camera}，主体为 ChunkPos；总上限
 * {@link CaptureConstants#SINGLEPLAYER_MAX_CHUNK_TICKETS}，超出截断并 WARN。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SingleplayerTicketHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final Comparator<ChunkPos> CHUNK_COMPARATOR =
            (left, right) -> Long.compare(ChunkPos.asLong(left.x, left.z), ChunkPos.asLong(right.x, right.z));
    private static final TicketType<ChunkPos> CAPTURE_TICKET =
            TicketType.<ChunkPos>create("captureapi_camera", CHUNK_COMPARATOR);
    private static final int TICKET_LEVEL = ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING);
    private static final Set<Long> HELD = ConcurrentHashMap.newKeySet();

    private static volatile Set<Long> lastRequested = Set.of();
    private static volatile MinecraftServer lastServer;
    private static volatile ResourceKey<Level> lastDimension;

    private SingleplayerTicketHolder() {
        throw new AssertionError("no instances");
    }

    /**
     * 更新目标区块集合（渲染线程调用；仅在单机集成服存在时生效）。
     *
     * @param chunks CaptureChunkPlanner 求得的区块集合（ChunkPos.asLong）
     */
    public static void update(Set<Long> chunks) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            // 远程/未进入单机：单机 ticket 不适用；清掉本地视图即可
            lastRequested = Set.of();
            return;
        }
        lastServer = server;
        Level level = minecraft.level;
        if (level == null) {
            releaseAll();
            return;
        }
        Set<Long> requested = new HashSet<>(chunks);
        if (requested.size() > CaptureConstants.SINGLEPLAYER_MAX_CHUNK_TICKETS) {
            Set<Long> limited = new HashSet<>();
            for (Long chunk : requested) {
                if (limited.size() >= CaptureConstants.SINGLEPLAYER_MAX_CHUNK_TICKETS) {
                    break;
                }
                limited.add(chunk);
            }
            LOGGER.warn("{} [CHUNK/TICKET] 目标区块 {} 超过上限 {}，截断",
                    CaptureConstants.LOG_MARKER, requested.size(),
                    CaptureConstants.SINGLEPLAYER_MAX_CHUNK_TICKETS);
            requested = limited;
        }
        Set<Long> previous = lastRequested;
        if (previous.equals(requested)) {
            return;
        }
        Set<Long> added = new HashSet<>(requested);
        added.removeAll(previous);
        Set<Long> removed = new HashSet<>(previous);
        removed.removeAll(requested);
        lastRequested = Set.copyOf(requested);
        lastDimension = level.dimension();
        ResourceKey<Level> dimension = lastDimension;
        Set<Long> addCopy = Set.copyOf(added);
        Set<Long> removeCopy = Set.copyOf(removed);
        try {
            server.execute(() -> apply(server, dimension, addCopy, removeCopy));
        } catch (Throwable t) {
            LOGGER.warn("{} [CHUNK/TICKET] 投递集成服失败：{}", CaptureConstants.LOG_MARKER, t.getMessage());
        }
    }

    private static void apply(MinecraftServer server, ResourceKey<Level> dimension,
                              Set<Long> added, Set<Long> removed) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            HELD.clear();
            return;
        }
        for (Long encoded : removed) {
            if (HELD.remove(encoded)) {
                ChunkPos pos = decode(encoded);
                level.getChunkSource().removeRegionTicket(CAPTURE_TICKET, pos, TICKET_LEVEL, pos);
            }
        }
        boolean firstAcquire = HELD.isEmpty() && !added.isEmpty();
        for (Long encoded : added) {
            if (HELD.size() >= CaptureConstants.SINGLEPLAYER_MAX_CHUNK_TICKETS) {
                LOGGER.warn("{} [CHUNK/TICKET] ticket 总量达到上限 {}，忽略剩余新增",
                        CaptureConstants.LOG_MARKER, CaptureConstants.SINGLEPLAYER_MAX_CHUNK_TICKETS);
                break;
            }
            if (HELD.add(encoded)) {
                ChunkPos pos = decode(encoded);
                level.getChunkSource().addRegionTicket(CAPTURE_TICKET, pos, TICKET_LEVEL, pos);
            }
        }
        if (firstAcquire) {
            LOGGER.info("{} [CHUNK/TICKET] 单机相机强加载启用：新增={}，held={}",
                    CaptureConstants.LOG_MARKER, added.size(), HELD.size());
        }
    }

    /** 世界退出/停服前释放全部单机 ticket（集成服线程执行）。 */
    public static void releaseAll() {
        lastRequested = Set.of();
        MinecraftServer server = lastServer;
        ResourceKey<Level> dimension = lastDimension;
        if (server == null || dimension == null) {
            HELD.clear();
            return;
        }
        try {
            server.execute(() -> releaseOnServer(server, dimension));
        } catch (Throwable t) {
            HELD.clear();
            LOGGER.warn("{} [CHUNK/TICKET] 释放投递失败：{}", CaptureConstants.LOG_MARKER, t.getMessage());
        }
    }

    private static void releaseOnServer(MinecraftServer server, ResourceKey<Level> dimension) {
        ServerLevel level = server.getLevel(dimension);
        if (level != null) {
            for (Long encoded : HELD) {
                ChunkPos pos = decode(encoded);
                level.getChunkSource().removeRegionTicket(CAPTURE_TICKET, pos, TICKET_LEVEL, pos);
            }
        }
        int released = HELD.size();
        HELD.clear();
        lastServer = null;
        lastDimension = null;
        if (released > 0) {
            LOGGER.info("{} [CHUNK/TICKET] 释放单机 ticket={}", CaptureConstants.LOG_MARKER, released);
        }
    }

    /** @return 当前已持有 ticket 数（诊断） */
    public static int heldTickets() {
        return HELD.size();
    }

    private static ChunkPos decode(long encoded) {
        return new ChunkPos(ChunkPos.getX(encoded), ChunkPos.getZ(encoded));
    }
}
