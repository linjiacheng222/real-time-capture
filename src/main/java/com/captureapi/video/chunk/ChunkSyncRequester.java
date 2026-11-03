package com.captureapi.video.chunk;

import com.captureapi.CaptureInterface;
import com.captureapi.core.mixin.ChunkMapAccessor;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.camera.CameraManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.mutable.MutableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端相机区块按需同步（S11 / T2.7.2-bis）。
 *
 * <p>SingleplayerTicketHolder 只保证服务端区块加载；本类调用
 * {@code ChunkMap.updateChunkTracking(..., wasAdded=true, ...)} 把相机目标区块主动同步到
 * 集成服唯一的本地玩家客户端，绕开原版玩家视距限制。每客户端 tick 限 8 块；卸载只针对
 * “相机集合外且玩家视距外”的区块，避免误删玩家正常区块。</p>
 *
 * <p>线程：diff/节流在客户端线程；ChunkMap 与发包任务经集成服 execute 投递服务端线程。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChunkSyncRequester {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final int SEND_PER_TICK = 8;
    private static final int MAX_SYNCED = CaptureConstants.SINGLEPLAYER_MAX_CHUNK_TICKETS;

    private static final Set<Long> DESIRED = ConcurrentHashMap.newKeySet();
    private static final Set<Long> SYNCED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong ENQUEUED = new AtomicLong();
    private static final AtomicLong SENT = new AtomicLong();
    private static final AtomicLong FORGOTTEN = new AtomicLong();

    private ChunkSyncRequester() {
        throw new AssertionError("no instances");
    }

    /** CaptureChunkPlanner 求取后调用（客户端线程）。 */
    public static void update(Set<Long> desiredChunks) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            releaseAll();
            return;
        }
        if (minecraft.level == null) {
            releaseAll();
            return;
        }
        Set<Long> capped = new HashSet<>(desiredChunks);
        if (capped.size() > MAX_SYNCED) {
            Set<Long> limited = new HashSet<>();
            for (Long encoded : capped) {
                if (limited.size() >= MAX_SYNCED) {
                    break;
                }
                limited.add(encoded);
            }
            LOGGER.warn("{} [CHUNK/SYNC] 目标区块 {} 超过上限 {}，截断",
                    CaptureConstants.LOG_MARKER, capped.size(), MAX_SYNCED);
            capped = limited;
        }
        DESIRED.clear();
        DESIRED.addAll(capped);
        long pending = 0L;
        for (Long encoded : DESIRED) {
            if (!SYNCED.contains(encoded)) {
                pending++;
            }
        }
        ENQUEUED.set(pending);
        if (pending > 0L) {
            LOGGER.debug("{} [CHUNK/SYNC] 入队={}（desired={}，synced={}）",
                    CaptureConstants.LOG_MARKER, pending, DESIRED.size(), SYNCED.size());
        }
    }

    /** 客户端 tick 驱动；每 tick 最多 8 个新块。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick();
    }

    /** 显式驱动（测试/生命周期兜底）。 */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.level == null) {
            releaseAll();
            return;
        }
        if (minecraft.player == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(minecraft.player.getUUID());
        if (player == null) {
            return;
        }
        // 相机删除后 active=0；没有消费者时不再保留同步区块
        if (CameraManager.get().active() == null && !DESIRED.isEmpty()) {
            DESIRED.clear();
        }
        int viewDistance = server.getPlayerList().getViewDistance();
        int playerChunkX = player.chunkPosition().x;
        int playerChunkZ = player.chunkPosition().z;

        List<Long> toSend = new ArrayList<>();
        for (Long encoded : DESIRED) {
            if (toSend.size() >= SEND_PER_TICK) {
                break;
            }
            if (!SYNCED.contains(encoded)) {
                toSend.add(encoded);
            }
        }
        List<Long> toForget = new ArrayList<>();
        for (Long encoded : SYNCED) {
            if (toForget.size() >= SEND_PER_TICK) {
                break;
            }
            if (DESIRED.contains(encoded)) {
                continue;
            }
            if (ChunkMap.isChunkInRange(playerChunkX, playerChunkZ,
                    ChunkPos.getX(encoded), ChunkPos.getZ(encoded), viewDistance)) {
                continue;
            }
            toForget.add(encoded);
        }
        if (toSend.isEmpty() && toForget.isEmpty()) {
            return;
        }
        for (Long encoded : toSend) {
            SYNCED.add(encoded);
            SENT.incrementAndGet();
        }
        for (Long encoded : toForget) {
            SYNCED.remove(encoded);
            FORGOTTEN.incrementAndGet();
        }
        ResourceKey<Level> dimension = minecraft.level.dimension();
        List<Long> sendCopy = List.copyOf(toSend);
        List<Long> forgetCopy = List.copyOf(toForget);
        try {
            server.execute(() -> apply(server, player, dimension, sendCopy, forgetCopy));
        } catch (Throwable t) {
            for (Long encoded : toSend) {
                SYNCED.remove(encoded);
            }
            LOGGER.warn("{} [CHUNK/SYNC] 投递集成服失败：{}", CaptureConstants.LOG_MARKER, t.getMessage());
            return;
        }
        LOGGER.info("{} [CHUNK/SYNC] 入队={} 已同步={} 卸载={}（desired={}，synced={}）",
                CaptureConstants.LOG_MARKER, toSend.size(), SENT.get(), FORGOTTEN.get(),
                DESIRED.size(), SYNCED.size());
    }

    private static void apply(MinecraftServer server, ServerPlayer player,
                              ResourceKey<Level> dimension, List<Long> toSend, List<Long> toForget) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return;
        }
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        for (Long encoded : toSend) {
            ChunkPos pos = decode(encoded);
            ((ChunkMapAccessor) chunkMap).captureapi$updateChunkTracking(player, pos,
                    new MutableObject<>(), true, false);
        }
        for (Long encoded : toForget) {
            ChunkPos pos = decode(encoded);
            player.connection.send(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
        }
    }

    /** 世界退出清理（不发包；客户端世界本身会卸载）。 */
    public static void releaseAll() {
        int synced = SYNCED.size();
        DESIRED.clear();
        SYNCED.clear();
        if (synced > 0) {
            LOGGER.info("{} [CHUNK/SYNC] 释放同步集合：{}", CaptureConstants.LOG_MARKER, synced);
        }
    }

    public static long syncedCount() {
        return SYNCED.size();
    }

    public static long sentTotal() {
        return SENT.get();
    }

    public static long forgottenTotal() {
        return FORGOTTEN.get();
    }

    public static long queuedCount() {
        return ENQUEUED.get();
    }

    private static ChunkPos decode(long encoded) {
        return new ChunkPos(ChunkPos.getX(encoded), ChunkPos.getZ(encoded));
    }
}
