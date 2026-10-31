package com.captureapi.net;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.frame.VideoFrameBus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP 视频出口（T4.4 MJPEG + T4.5 单帧）。
 *
 * <p>路由：`GET /camera/{id}/stream`（multipart/x-mixed-replace）与 `GET /camera/{id}/frame`
 * （裸 JPEG，X-Frame-Timestamp 头）；id=0 表示当前 active 相机。两者与 WS 推流共享同一
 * {@link VideoFrameBus} 最新帧缓存（同源）。只读缓存、不触发渲染；无帧时 frame 返回 204。</p>
 *
 * <p>MJPEG 每连接独立工作线程（HttpServer 缓存线程池 + 连接上限），写异常即视为客户端断开并
 * 清理；慢消费者阻塞只占本连接。帧率由最新帧变化驱动，不额外定时编码。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class HttpVideoHandler implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final long STREAM_POLL_MS = 10L;
    private static final long SLOW_WRITE_NANOS = 5_000_000_000L;

    private final NetworkServiceManager manager;
    private final AtomicLong streamSlowDisconnects = new AtomicLong();

    public HttpVideoHandler(NetworkServiceManager manager) {
        this.manager = manager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"ok\":false,\"error\":{\"code\":\"E_METHOD_NOT_ALLOWED\"}}");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Route route = Route.parse(path);
            if (route == null) {
                sendJson(exchange, 404, "{\"ok\":false,\"error\":{\"code\":\"E_NOT_FOUND\"}}");
                return;
            }
            int active = CameraManager.get().activeCameraId();
            if (route.cameraId != 0 && route.cameraId != active) {
                sendJson(exchange, 404, "{\"ok\":false,\"error\":{\"code\":\"E_CAMERA_NOT_ACTIVE\"}}");
                return;
            }
            if (route.cameraId == 0 && active == 0) {
                if (route.stream) {
                    sendJson(exchange, 404, "{\"ok\":false,\"error\":{\"code\":\"E_NO_ACTIVE_CAMERA\"}}");
                } else {
                    // 无 active 相机即无帧：单帧路由按 T4.5.1 立即 204，不挂起
                    sendNoContent(exchange);
                }
                return;
            }
            if (route.stream) {
                handleStream(exchange);
            } else {
                handleFrame(exchange);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            manager.recordError();
            LOGGER.warn("{} [HTTP/VIDEO] 请求处理异常：{}", CaptureConstants.LOG_MARKER, t.toString());
            try {
                sendJson(exchange, 500, "{\"ok\":false,\"error\":{\"code\":\"E_INTERNAL\"}}");
            } catch (IOException ignored) {
                // 响应已开始/连接断开
            }
        } finally {
            exchange.close();
        }
    }

    private void handleFrame(HttpExchange exchange) throws IOException {
        JpegFrame frame = VideoFrameBus.get().latest();
        if (frame == null) {
            sendNoContent(exchange);
            return;
        }
        byte[] jpeg = frame.jpeg();
        exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Frame-Timestamp", Long.toString(frame.timestampNanos()));
        exchange.getResponseHeaders().set("X-Frame-Width", Integer.toString(frame.width()));
        exchange.getResponseHeaders().set("X-Frame-Height", Integer.toString(frame.height()));
        exchange.sendResponseHeaders(200, jpeg.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(jpeg);
        }
    }

    private void handleStream(HttpExchange exchange) throws IOException {
        if (!manager.tryAcquireHttpStream()) {
            sendJson(exchange, 503, "{\"ok\":false,\"error\":{\"code\":\"E_CONNECTION_LIMIT\"}}");
            return;
        }
        long lastTimestamp = 0L;
        long lastWriteNanos = com.captureapi.core.time.CaptureClock.get().nowNanos();
        String boundary = CaptureConstants.MJPEG_BOUNDARY;
        exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=" + boundary);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            while (true) {
                JpegFrame frame = VideoFrameBus.get().latest();
                if (frame != null && frame.timestampNanos() != lastTimestamp) {
                    lastTimestamp = frame.timestampNanos();
                    long writeStart = com.captureapi.core.time.CaptureClock.get().nowNanos();
                    output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    output.write(("Content-Type: image/jpeg\r\n"
                            + "Content-Length: " + frame.jpeg().length + "\r\n"
                            + "X-Frame-Timestamp: " + lastTimestamp + "\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    output.write(frame.jpeg());
                    output.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                    lastWriteNanos = com.captureapi.core.time.CaptureClock.get().nowNanos();
                    long writeNanos = lastWriteNanos - writeStart;
                    if (writeNanos > SLOW_WRITE_NANOS) {
                        streamSlowDisconnects.incrementAndGet();
                        LOGGER.warn("{} [HTTP/MJPEG] 慢消费者写阻塞 {}ms，断开", CaptureConstants.LOG_MARKER,
                                writeNanos / 1_000_000L);
                        return;
                    }
                } else if (com.captureapi.core.time.CaptureClock.get().nowNanos()
                        - lastWriteNanos > 1_000_000_000L) {
                    // 无新帧时周期写空白心跳：及时发现客户端断开并释放连接名额
                    output.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    output.flush();
                    lastWriteNanos = com.captureapi.core.time.CaptureClock.get().nowNanos();
                }
                try {
                    Thread.sleep(STREAM_POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IOException e) {
            // 客户端断开：正常清理
        } finally {
            manager.releaseHttpStream();
        }
    }

    private static void sendNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    public long streamSlowDisconnects() {
        return streamSlowDisconnects.get();
    }

    private record Route(int cameraId, boolean stream) {
        private static Route parse(String path) {
            if (path == null) {
                return null;
            }
            String[] parts = path.split("/");
            // ["", "camera", "{id}", "frame|stream"]
            if (parts.length != 4 || !"camera".equals(parts[1])) {
                return null;
            }
            int id;
            try {
                id = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (id < 0) {
                return null;
            }
            if ("frame".equals(parts[3])) {
                return new Route(id, false);
            }
            if ("stream".equals(parts[3])) {
                return new Route(id, true);
            }
            return null;
        }
    }
}
