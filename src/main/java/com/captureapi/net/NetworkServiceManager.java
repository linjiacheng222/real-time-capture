package com.captureapi.net;

import com.captureapi.CaptureInterface;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.constant.ProtocolConstants;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对外网络服务生命周期（T4.1）。
 *
 * <p>HTTP 使用 JDK {@link HttpServer}（绑定 {@code net_port}，默认 25580）；WebSocket 使用
 * Java-WebSocket（绑定 {@code net_port+1}，避免与 HTTP 监听冲突；端口推导在健康端点/日志明示）。
 * 默认仅绑定 127.0.0.1；{@code lan_enabled=true} 且未强制本地时绑定通配地址并双通道警告。
 * token 可选：HTTP 校验 {@code X-CaptureAPI-Token}，WS 校验握手 query {@code token}，/health 免鉴权。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class NetworkServiceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final NetworkServiceManager INSTANCE = new NetworkServiceManager();
    private static final int HTTP_THREADS = 2;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong startedAtNanos = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicInteger httpStreams = new AtomicInteger();
    private final AtomicLong httpSlowDisconnects = new AtomicLong();

    private HttpServer httpServer;
    private ExecutorService httpExecutor;
    private CaptureWebSocketServer webSocketServer;
    private volatile String token;
    private volatile String bindDescription = "127.0.0.1";
    private volatile boolean lanWarningShown;
    private int httpPort;
    private int wsPort;

    private NetworkServiceManager() {
    }

    public static NetworkServiceManager get() {
        return INSTANCE;
    }

    /** 启动 HTTP/WS 两服务（幂等；客户端 setup 调用）。 */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "captureapi-net-shutdown"));
        CaptureParams params = CaptureParams.get();
        httpPort = params.netPort();
        boolean loopbackOnly = params.bindLocalOnly() || !params.lanEnabled();
        InetAddress bindAddress;
        try {
            // 强制 IPv4 回环（JDK getLoopbackAddress 在部分环境返回 ::1，与既定 127.0.0.1 语义不符）
            bindAddress = loopbackOnly ? InetAddress.getByName("127.0.0.1") : null;
        } catch (java.net.UnknownHostException e) {
            bindAddress = null;
        }
        bindDescription = loopbackOnly ? "127.0.0.1" : "0.0.0.0";
        if (params.tokenEnabled()) {
            token = CaptureWebSocketServer.randomToken();
            LOGGER.info("{} [NET/TOKEN] 服务 token 已生成（仅本次展示，请勿泄露）：{}",
                    CaptureConstants.LOG_MARKER, token);
        }
        try {
            startHttp(bindAddress);
            startWebSocket(bindAddress);
            startedAtNanos.set(com.captureapi.core.time.CaptureClock.get().nowNanos());
            LOGGER.info("{} [NET/START] HTTP 就绪：http://{}:{}/health；WS 就绪：ws://{}:{}/；绑定={}，token={}",
                    CaptureConstants.LOG_MARKER, bindDescription, httpPort, bindDescription, wsPort,
                    bindDescription, token != null);
            if (!loopbackOnly) {
                showLanWarning();
            }
        } catch (BindException e) {
            errors.incrementAndGet();
            LOGGER.error("{} [NET/BIND_FAIL] 端口 {} 被占用（HTTP/WS 未启动）：请修改 net_port；游戏继续运行",
                    CaptureConstants.LOG_MARKER, e.getMessage());
            stop();
        } catch (Throwable t) {
            errors.incrementAndGet();
            LOGGER.error("{} [NET/START_FAIL] 网络服务启动失败（游戏继续运行）", CaptureConstants.LOG_MARKER, t);
            stop();
        }
    }

    private void startHttp(InetAddress bindAddress) throws IOException {
        InetSocketAddress address = bindAddress == null ? new InetSocketAddress("0.0.0.0", httpPort)
                : new InetSocketAddress(bindAddress, httpPort);
        httpServer = HttpServer.create(address, 0);
        // MJPEG 长连接需要每连接一个线程：缓存线程池 + 连接上限池（I4.4.2/I4.4.3）
        httpExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "captureapi-http");
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(httpExecutor);
        httpServer.createContext("/health", this::handleHealth);
        httpServer.createContext("/", exchange -> {
            if (!authorize(exchange)) {
                send(exchange, ProtocolConstants.HTTP_UNAUTHORIZED, "application/json",
                        "{\"ok\":false,\"code\":\"E_UNAUTHORIZED\"}");
                return;
            }
            send(exchange, ProtocolConstants.HTTP_NOT_FOUND, "application/json", "{\"ok\":false,\"code\":\"E_NOT_FOUND\"}");
        });
        httpServer.start();
        LOGGER.info("{} [NET/HTTP] 监听 http://{}:{}（回环={}）", CaptureConstants.LOG_MARKER,
                bindDescription, httpPort, bindAddress != null);
    }

    private void startWebSocket(InetAddress bindAddress) {
        wsPort = httpPort < ProtocolConstants.MAX_TCP_PORT ? httpPort + 1 : -1;
        if (wsPort < 0) {
            LOGGER.warn("{} [NET/WS] net_port=65535 无可用 WS 端口，WS 服务不启动", CaptureConstants.LOG_MARKER);
            return;
        }
        InetSocketAddress address = bindAddress == null ? new InetSocketAddress("0.0.0.0", wsPort)
                : new InetSocketAddress(bindAddress, wsPort);
        webSocketServer = new CaptureWebSocketServer(address, this);
        try {
            webSocketServer.start();
        } catch (java.io.IOException e) {
            errors.incrementAndGet();
            webSocketServer = null;
            LOGGER.error("{} [NET/WS] 绑定 ws://{}:{} 失败：{}", CaptureConstants.LOG_MARKER,
                    bindDescription, wsPort, e.getMessage());
            return;
        }
        LOGGER.info("{} [NET/WS] 监听 ws://{}:{}/", CaptureConstants.LOG_MARKER, bindDescription, wsPort);
        StreamPushService.get().start(webSocketServer);
    }

    /** 停止两服务并释放端口（幂等）。 */
    public synchronized void stop() {
        if (!running.get() && httpServer == null && webSocketServer == null) {
            return;
        }
        running.set(false);
        StreamPushService.get().stop();
        com.captureapi.net.recording.RecordingSession.get().stop();
        if (webSocketServer != null) {
            try {
                webSocketServer.stop(1000);
            } catch (Throwable t) {
                LOGGER.warn("{} [NET/STOP] WS 停止异常", CaptureConstants.LOG_MARKER, t);
            }
            webSocketServer = null;
        }
        if (httpServer != null) {
            try {
                httpServer.stop(0);
            } catch (Throwable t) {
                LOGGER.warn("{} [NET/STOP] HTTP 停止异常", CaptureConstants.LOG_MARKER, t);
            }
            httpServer = null;
        }
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        LOGGER.info("{} [NET/STOP] 网络服务已停止，端口已释放（http={}，ws={}）",
                CaptureConstants.LOG_MARKER, httpPort, wsPort);
    }

    /** @return 服务是否运行 */
    public boolean isRunning() {
        return running.get();
    }

    /** @return WS 当前连接数 */
    public int connectionCount() {
        CaptureWebSocketServer server = webSocketServer;
        return server == null ? 0 : server.connectionCount();
    }

    /** @return 被拒绝的连接数 */
    public long rejectedConnections() {
        return rejectedConnections.get();
    }

    void recordRejectedConnection() {
        rejectedConnections.incrementAndGet();
    }

    void recordError() {
        errors.incrementAndGet();
    }

    /** MJPEG 慢写断开计数（I4.4.3/I4.6.2）。 */
    public void recordHttpSlowDisconnect() {
        httpSlowDisconnects.incrementAndGet();
    }

    /** @return 是否校验 token 请求 */
    public boolean tokenEnabled() {
        return token != null;
    }

    /**
     * WS 握手 token 校验。
     *
     * @param resourceDescriptor 握手资源描述（如 {@code /?token=xxx}）
     * @return 通过返回 true
     */
    boolean authorizeWs(String resourceDescriptor) {
        if (token == null) {
            return true;
        }
        if (resourceDescriptor == null) {
            return false;
        }
        int q = resourceDescriptor.indexOf('?');
        if (q < 0) {
            return false;
        }
        for (String pair : resourceDescriptor.substring(q + 1).split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals("token") && kv[1].equals(token)) {
                return true;
            }
        }
        return false;
    }

    /** HTTP token 校验（/health 例外由调用方处理）。 */
    boolean authorize(HttpExchange exchange) {
        if (token == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("X-CaptureAPI-Token");
        if (token.equals(header)) {
            return true;
        }
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals("token") && kv[1].equals(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, ProtocolConstants.HTTP_METHOD_NOT_ALLOWED, "application/json", "{\"ok\":false,\"code\":\"E_METHOD_NOT_ALLOWED\"}");
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("running", running.get());
        json.addProperty("http", httpServer != null);
        json.addProperty("ws", webSocketServer != null);
        json.addProperty("ws_port", wsPort);
        json.addProperty("bind", bindDescription);
        json.addProperty("lan_enabled", !"127.0.0.1".equals(bindDescription));
        json.addProperty("connections", connectionCount());
        json.addProperty("max_connections", CaptureParams.get().maxConnections());
        json.addProperty("max_qps", CaptureParams.get().maxQps());
        json.addProperty("rejected_connections", rejectedConnections.get());
        json.addProperty("errors", errors.get());
        long started = startedAtNanos.get();
        long uptimeMs = started == 0L ? 0L
                : (com.captureapi.core.time.CaptureClock.get().nowNanos() - started) / 1_000_000L;
        json.addProperty("uptime_ms", uptimeMs);
        json.addProperty("token_required", tokenEnabled());
        json.addProperty("slow_disconnects", webSocketServer == null ? 0L : webSocketServer.slowDisconnectCount());
        json.addProperty("http_streams", httpStreams.get());
        json.addProperty("http_slow_disconnects", httpSlowDisconnects.get());
        json.addProperty("video_frames_published", com.captureapi.video.frame.VideoFrameBus.get().publishedCount());
        json.addProperty("game_fps", net.minecraft.client.Minecraft.getInstance().getFps());
        json.addProperty("shader_backend", com.captureapi.video.shader.ShaderCompatHooks.backend());
        json.addProperty("shader_detected", com.captureapi.video.shader.ShaderCompatHooks.detected());
        json.addProperty("shader_active", com.captureapi.video.shader.ShaderCompatHooks.isActive());
        json.addProperty("remote_ready", RemoteCaptureClient.isReady());
        json.addProperty("remote_requests", RemoteCaptureClient.requests());
        json.addProperty("remote_accepted", RemoteCaptureClient.accepted());
        json.addProperty("remote_last_code", RemoteCaptureClient.lastCode());
        json.addProperty("multicam_cached_cameras",
                com.captureapi.video.camera.CameraFrameStore.size());
        json.addProperty("multicam_interval_ms",
                com.captureapi.core.constant.CaptureConstants.MULTI_CAMERA_INTERVAL_MS);
        json.addProperty("offscreen_rendered_frames", com.captureapi.video.render.OffscreenRenderPipeline.renderedFrames());
        json.addProperty("readback_frames", com.captureapi.video.render.OffscreenRenderPipeline.readbackFrames());
        json.addProperty("encoded_frames", com.captureapi.video.encode.EncodingPipeline.get().encodedFrames());
        json.addProperty("encode_dropped_frames", com.captureapi.video.encode.EncodingPipeline.get().droppedFrames());
        json.addProperty("encode_failed_frames", com.captureapi.video.encode.EncodingPipeline.get().failedFrames());
        json.addProperty("audio_ring_written", com.captureapi.audio.AudioRingBuffer.get().writtenPolls());
        json.addProperty("audio_ring_dropped", com.captureapi.audio.AudioRingBuffer.get().droppedPolls());
        json.addProperty("audio_ring_silent", com.captureapi.audio.AudioRingBuffer.get().silentPolls());
        json.addProperty("pipeline_failed_frames", com.captureapi.video.render.OffscreenRenderPipeline.failedFrames());
        json.addProperty("render_backpressure", "drop_old_keep_latest");
        json.addProperty("sync_delta_ms_avg", com.captureapi.audio.sync.SyncDeviationMonitor.get().averageDeltaMs());
        json.addProperty("sync_delta_samples", com.captureapi.audio.sync.SyncDeviationMonitor.get().deltaSamples());
        json.addProperty("sync_regressions", com.captureapi.audio.sync.SyncDeviationMonitor.get().regressions());
        json.addProperty("sync_warnings", com.captureapi.audio.sync.SyncDeviationMonitor.get().warnings());
        send(exchange, ProtocolConstants.HTTP_OK, "application/json; charset=utf-8", json.toString());
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void showLanWarning() {
        if (lanWarningShown) {
            return;
        }
        lanWarningShown = true;
        String message = "服务已对局域网开放，任何同网设备可访问（HTTP 端口 " + httpPort + "，WS 端口 " + wsPort + "）";
        LOGGER.warn("{} [NET/SECURITY] {}", CaptureConstants.LOG_MARKER, message);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal("[捕获接口] " + message), false);
            }
        });
    }

    /**
     * 尝试占用一个 MJPEG 流名额（与 WS 连接共享 max_connections 上限）。
     *
     * @return 成功占用返回 true；超限 false
     */
    public boolean tryAcquireHttpStream() {
        int max = CaptureParams.get().maxConnections();
        int ws = connectionCount();
        while (true) {
            int current = httpStreams.get();
            if (ws + current >= max) {
                rejectedConnections.incrementAndGet();
                return false;
            }
            if (httpStreams.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** 释放 MJPEG 流名额（幂等）。 */
    public void releaseHttpStream() {
        httpStreams.updateAndGet(value -> Math.max(0, value - 1));
    }

    /** @return 当前 MJPEG 流连接数 */
    public int httpStreamCount() {
        return httpStreams.get();
    }

    /** 供出口注册 HTTP 路由（T4.4/T4.5 使用；token 已在入口统一处理）。 */
    public void registerHttpContext(String path, HttpHandler handler) {
        HttpServer server = httpServer;
        if (server != null) {
            server.createContext(path, exchange -> {
                if (!authorize(exchange)) {
                    send(exchange, ProtocolConstants.HTTP_UNAUTHORIZED, "application/json", "{\"ok\":false,\"code\":\"E_UNAUTHORIZED\"}");
                    return;
                }
                handler.handle(exchange);
            });
        }
    }

    /** @return HTTP 端口 */
    public int httpPort() {
        return httpPort;
    }

    /** @return WS 端口 */
    public int wsPort() {
        return wsPort;
    }

    /** @return 当前 token（仅调试/内部；不写入健康端点） */
    public String tokenForInternalUse() {
        return token;
    }

    /** 单例 ModID 便捷引用（避免未使用 import 告警）。 */
    public String modId() {
        return CaptureInterface.MODID;
    }
}
