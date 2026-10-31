package com.captureapi.server;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.network.FriendlyByteBuf;

/**
 * CaptureAPI 可选服务端组件（M9）。
 *
 * <p>实现 M2/T2.9 冻结的四消息服务端侧：握手（协议版本）、区块申请、释放、断线/停服回收。
 * 受理受 T9.5 权限与 T9.3 频率/单玩家/全局三层守卫。独立 jar，modid=captureapi_server。</p>
 */
@Mod(CaptureApiServer.MODID)
public final class CaptureApiServer {

    public static final String MODID = "captureapi_server";
    public static final int PROTOCOL_VERSION = 1;

    private static final String CHANNEL_VERSION = Integer.toString(PROTOCOL_VERSION);
    private static SimpleChannel channel;
    private static final TicketManager TICKETS = new TicketManager();
    private static final Map<UUID, Integer> HANDSHAKES = new ConcurrentHashMap<>();

    public CaptureApiServer() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(MODID, "main"),
                () -> CHANNEL_VERSION, version -> true, version -> true);
        channel.registerMessage(0, HandshakeRequest.class,
                (message, buffer) -> buffer.writeInt(message.protocolVersion()),
                buffer -> new HandshakeRequest(buffer.readInt()),
                (message, contextSupplier) -> {
                    var context = contextSupplier.get();
                    context.enqueueWork(() -> handleHandshake(context.getSender(), message));
                    context.setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(1, ChunkRequest.class,
                (message, buffer) -> writeLongs(buffer, message.chunks()),
                buffer -> new ChunkRequest(readLongs(buffer)),
                (message, contextSupplier) -> {
                    var context = contextSupplier.get();
                    context.enqueueWork(() -> handleChunkRequest(context.getSender(), message));
                    context.setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(2, ChunkRelease.class,
                (message, buffer) -> {
                    buffer.writeBoolean(message.all());
                    writeLongs(buffer, message.chunks());
                },
                buffer -> new ChunkRelease(buffer.readBoolean(), readLongs(buffer)),
                (message, contextSupplier) -> {
                    var context = contextSupplier.get();
                    context.enqueueWork(() -> handleChunkRelease(context.getSender(), message));
                    context.setPacketHandled(true);
                }, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(3, ServerResponse.class,
                (message, buffer) -> {
                    buffer.writeBoolean(message.ok());
                    buffer.writeUtf(message.code(), 64);
                    buffer.writeInt(message.accepted());
                },
                buffer -> new ServerResponse(buffer.readBoolean(), buffer.readUtf(64), buffer.readInt()),
                (message, contextSupplier) -> contextSupplier.get().setPacketHandled(true),
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static void handleHandshake(ServerPlayer player, HandshakeRequest request) {
        if (player == null) {
            return;
        }
        if (request.protocolVersion() == PROTOCOL_VERSION) {
            HANDSHAKES.put(player.getUUID(), PROTOCOL_VERSION);
            send(player, new HandshakeResponse(true, PROTOCOL_VERSION, "OK"));
        } else {
            HANDSHAKES.remove(player.getUUID());
            send(player, new HandshakeResponse(false, PROTOCOL_VERSION,
                    "协议版本不匹配：客户端=" + request.protocolVersion() + "，服务端=" + PROTOCOL_VERSION));
        }
    }

    private static void handleChunkRequest(ServerPlayer player, ChunkRequest request) {
        if (player == null) {
            return;
        }
        if (HANDSHAKES.getOrDefault(player.getUUID(), 0) != PROTOCOL_VERSION) {
            send(player, new ServerResponse(false, "E_HANDSHAKE_REQUIRED", 0));
            return;
        }
        if (!Permissions.get().isAllowed(player)) {
            send(player, new ServerResponse(false, "E_NO_PERMISSION", 0));
            return;
        }
        TicketManager.Result result = TICKETS.request(player, request.chunks());
        send(player, new ServerResponse(result.ok(), result.code(), result.accepted()));
    }

    private static void handleChunkRelease(ServerPlayer player, ChunkRelease release) {
        if (player == null) {
            return;
        }
        if (!Permissions.get().isAllowed(player)) {
            send(player, new ServerResponse(false, "E_NO_PERMISSION", 0));
            return;
        }
        TicketManager.Result result = TICKETS.release(player, release.all(), release.chunks());
        send(player, new ServerResponse(result.ok(), result.code(), result.accepted()));
    }

    private static void send(ServerPlayer player, Object message) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        Permissions.get().reload();
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HANDSHAKES.remove(player.getUUID());
            TICKETS.releaseAll(player);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        HANDSHAKES.clear();
        TICKETS.clear();
    }

    private static void writeLongs(FriendlyByteBuf buffer, long[] values) {
        buffer.writeVarInt(values.length);
        for (long value : values) {
            buffer.writeLong(value);
        }
    }

    private static long[] readLongs(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > 4096) {
            throw new IllegalArgumentException("非法区块数量：" + size);
        }
        long[] values = new long[size];
        for (int i = 0; i < size; i++) {
            values[i] = buffer.readLong();
        }
        return values;
    }

    /** 握手请求。 */
    public record HandshakeRequest(int protocolVersion) {
    }

    /** 握手响应。 */
    public record HandshakeResponse(boolean supported, int protocolVersion, String message) {
    }

    /** 区块申请。 */
    public record ChunkRequest(long[] chunks) {
    }

    /** 区块释放。 */
    public record ChunkRelease(boolean all, long[] chunks) {
    }

    /** 通用响应。 */
    public record ServerResponse(boolean ok, String code, int accepted) {
    }
}
