package com.captureapi.net;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.chunk.CaptureChunkPlanner;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M9 服务端组件的客户端申请侧（P1.3/T2.7.3）。
 *
 * <p>与可选的 {@code captureapi_server} 组件使用同一 SimpleChannel（modid=captureapi_server,
 * channel=main）。远程 MP 连接且服务端注册了该通道时：登录后握手 → 将
 * {@link CaptureChunkPlanner} 的区块集合差分发送 → 接收受理码；断线/退出自动清理。
 * 单机或服务端未安装组件时 {@code isRemotePresent=false}，完全 no-op。</p>
 *
 * <p>红线：客户端仅通过 Forge 通道申请 ticket，不直接加载区块；服务端权限/限额由 M9 组件负责。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RemoteCaptureClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final String CHANNEL_MODID = "captureapi_server";
    private static final int PROTOCOL_VERSION = 1;
    private static final String CHANNEL_VERSION = Integer.toString(PROTOCOL_VERSION);
    /** 单次申请的区块上限（与服务端 readLongs 上限一致）。 */
    private static final int MAX_REQUEST_CHUNKS = 4096;
    /** 响应 code 最大长度（协议字段）。 */
    private static final int CODE_MAX_LENGTH = 64;
    /** 握手 message 最大长度（协议字段）。 */
    private static final int MESSAGE_MAX_LENGTH = 256;

    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static volatile boolean ready;
    private static volatile String lastCode = "";
    private static Set<Long> lastSent = Set.of();

    private static SimpleChannel channel;

    private RemoteCaptureClient() {
        throw new AssertionError("no instances");
    }

    private static synchronized SimpleChannel channel() {
        if (channel == null) {
            SimpleChannel created = NetworkRegistry.newSimpleChannel(
                    new ResourceLocation(CHANNEL_MODID, "main"),
                    () -> CHANNEL_VERSION, version -> true, version -> true);
            created.registerMessage(0, HandshakeRequest.class,
                    (message, buffer) -> buffer.writeInt(message.protocolVersion()),
                    buffer -> new HandshakeRequest(buffer.readInt()),
                    (message, context) -> context.get().setPacketHandled(true),
                    java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
            created.registerMessage(1, ChunkRequest.class,
                    (message, buffer) -> writeLongs(buffer, message.chunks()),
                    buffer -> new ChunkRequest(readLongs(buffer)),
                    (message, context) -> context.get().setPacketHandled(true),
                    java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
            created.registerMessage(2, ChunkRelease.class,
                    (message, buffer) -> {
                        buffer.writeBoolean(message.all());
                        writeLongs(buffer, message.chunks());
                    },
                    buffer -> new ChunkRelease(buffer.readBoolean(), readLongs(buffer)),
                    (message, context) -> context.get().setPacketHandled(true),
                    java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
            created.registerMessage(3, ServerResponse.class,
                    (message, buffer) -> {
                        buffer.writeBoolean(message.ok());
                        buffer.writeUtf(message.code(), CODE_MAX_LENGTH);
                        buffer.writeInt(message.accepted());
                    },
                    buffer -> new ServerResponse(buffer.readBoolean(), buffer.readUtf(CODE_MAX_LENGTH), buffer.readInt()),
                    (message, context) -> {
                        var ctx = context.get();
                        ctx.enqueueWork(() -> onServerResponse(message));
                        ctx.setPacketHandled(true);
                    },
                    java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            created.registerMessage(4, HandshakeResponse.class,
                    (message, buffer) -> {
                        buffer.writeBoolean(message.supported());
                        buffer.writeInt(message.protocolVersion());
                        buffer.writeUtf(message.message(), MESSAGE_MAX_LENGTH);
                    },
                    buffer -> new HandshakeResponse(buffer.readBoolean(), buffer.readInt(), buffer.readUtf(MESSAGE_MAX_LENGTH)),
                    (message, context) -> {
                        var ctx = context.get();
                        ctx.enqueueWork(() -> onHandshakeResponse(message));
                        ctx.setPacketHandled(true);
                    },
                    java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
            channel = created;
        }
        return channel;
    }

    /** 登录后尝试握手；服务端未安装组件则 no-op。 */
    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        ready = false;
        lastCode = "";
        lastSent = Set.of();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.hasSingleplayerServer()) {
            LOGGER.debug("{} [REMOTE] 单机世界，跳过 M9 远程申请", CaptureConstants.LOG_MARKER);
            return;
        }
        try {
            if (!channel().isRemotePresent(event.getConnection())) {
                LOGGER.info("{} [REMOTE] 服务端未安装 captureapi_server 组件，远程区块申请关闭", CaptureConstants.LOG_MARKER);
                return;
            }
            channel().sendToServer(new HandshakeRequest(PROTOCOL_VERSION));
            LOGGER.info("{} [REMOTE] 已发送 M9 握手（协议 v{}）", CaptureConstants.LOG_MARKER, PROTOCOL_VERSION);
        } catch (Throwable t) {
            LOGGER.warn("{} [REMOTE] 握手失败：{}", CaptureConstants.LOG_MARKER, t.getMessage());
        }
    }

    /** 退出时清理本地申请状态（服务端在 logout 事件释放 ticket）。 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ready = false;
        lastCode = "";
        lastSent = Set.of();
    }

    private static void onHandshakeResponse(HandshakeResponse response) {
        ready = response.supported();
        lastCode = ready ? "OK" : response.message();
        LOGGER.info("{} [REMOTE] 握手响应：supported={}，serverProtocol={}，msg={}", CaptureConstants.LOG_MARKER,
                response.supported(), response.protocolVersion(), response.message());
        if (ready) {
            Set<Long> current = CaptureChunkPlanner.get().current().chunks();
            if (!current.isEmpty()) {
                onChunks(current);
            }
        }
    }

    private static void onServerResponse(ServerResponse response) {
        lastCode = response.code();
        if (response.ok()) {
            ACCEPTED.addAndGet(response.accepted());
        }
        LOGGER.debug("{} [REMOTE] 受理响应：ok={}，code={}，accepted={}", CaptureConstants.LOG_MARKER,
                response.ok(), response.code(), response.accepted());
    }

    /**
     * 区块集合变化回调（由 CaptureChunkPlanner 在重算后调用）。
     *
     * @param chunks 当前需要的区块集合
     */
    public static void onChunks(Set<Long> chunks) {
        if (!ready) {
            return;
        }
        try {
            Set<Long> added = new HashSet<>(chunks);
            added.removeAll(lastSent);
            Set<Long> removed = new HashSet<>(lastSent);
            removed.removeAll(chunks);
            if (!added.isEmpty()) {
                if (added.size() > MAX_REQUEST_CHUNKS) {
                    LOGGER.warn("{} [REMOTE] 本次申请 {} 区块超过上限 {}，截断", CaptureConstants.LOG_MARKER,
                            added.size(), MAX_REQUEST_CHUNKS);
                    added = added.stream().limit(MAX_REQUEST_CHUNKS).collect(java.util.stream.Collectors.toSet());
                }
                channel().sendToServer(new ChunkRequest(toLongs(added)));
                REQUESTS.incrementAndGet();
            }
            if (!removed.isEmpty()) {
                channel().sendToServer(new ChunkRelease(false, toLongs(removed)));
            }
            lastSent = Set.copyOf(chunks);
        } catch (Throwable t) {
            LOGGER.warn("{} [REMOTE] 区块申请发送失败：{}", CaptureConstants.LOG_MARKER, t.getMessage());
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static long requests() {
        return REQUESTS.get();
    }

    public static long accepted() {
        return ACCEPTED.get();
    }

    public static String lastCode() {
        return lastCode;
    }

    private static long[] toLongs(Set<Long> values) {
        long[] out = new long[values.size()];
        int i = 0;
        for (Long value : values) {
            out[i++] = value;
        }
        return out;
    }

    private static void writeLongs(FriendlyByteBuf buffer, long[] values) {
        buffer.writeVarInt(values.length);
        for (long value : values) {
            buffer.writeLong(value);
        }
    }

    private static long[] readLongs(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_REQUEST_CHUNKS) {
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
