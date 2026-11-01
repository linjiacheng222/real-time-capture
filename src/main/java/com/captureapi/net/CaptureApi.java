package com.captureapi.net;

import com.captureapi.audio.AudioRingBuffer;
import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.video.camera.CameraCreateParams;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraSpec;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.frame.VideoFrameBus;
import com.captureapi.video.stream.VideoStreamHub;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.imageio.ImageIO;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组内 Java API 门面（T4.7 / I4.7.1，二次开发唯一编程入口）。
 *
 * <p>同源约束：所有画面方法转发到与网络出口相同的 {@link VideoFrameBus} / {@link VideoStreamHub}，
 * 音频转发到 {@link AudioRingBuffer}，相机管理转发到 {@link CameraManager}；门面不缓存、不加工。
 * 线程语义：单帧/最新帧任意线程只读；调取返回 CompletableFuture（完成在渲染线程）；
 * 相机管理方法可任意线程（CameraManager 线程安全）。</p>
 *
 * <p>边界：音频截面（虚拟位）语义与实现待 W-071 冻结，当前以明确 UnsupportedOperationException
 * 标注（不为假实现）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CaptureApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    /** 单帧轮询最大尝试次数（避免无限等待异常状态）。 */
    private static final int MAX_FRAME_POLLS = 10000;

    private CaptureApi() {
        throw new AssertionError("no instances");
    }

    // ===== 画面 =====

    /** @return 最新 JPEG 帧（任意线程；从未发布时 null；与网络出口同源） */
    public static JpegFrame latestFrame() {
        return VideoFrameBus.get().latest();
    }

    /**
     * 实时调取一帧（临时相机，异步；与流式同帧互斥）。
     *
     * @param params        临时相机参数
     * @param timeoutMillis 超时毫秒
     * @return JPEG 帧 Future（世界未就绪/超时/渲染冲突时异常完成）
     */
    public static CompletableFuture<JpegFrame> grabFrame(CameraCreateParams params, long timeoutMillis) {
        return VideoStreamHub.get().requestOneShot(params, timeoutMillis);
    }

    /**
     * 统一帧获取入口（T4.9 / I4.9.1）：同步阻塞至帧就绪，按请求输出文件或 HTTP URL。
     *
     * <p>不可在渲染线程调用（临时调取依赖渲染线程完成 Future，阻塞会死锁）。</p>
     *
     * @param request 帧获取参数
     * @return 文件路径或 HTTP URL＋元数据
     * @throws IOException 编码/写文件/调取失败
     */
    public static FrameResult captureFrame(FrameRequest request) throws IOException {
        if (GlThreadGuard.isOnRenderThread()) {
            throw new IllegalStateException("captureFrame 不可在渲染线程调用（会阻塞渲染/调取完成）");
        }
        if (request.continuous()) {
            throw new UnsupportedOperationException("continuous=true 持续帧获取未实现（流式请用 WS/MJPEG）");
        }
        if (request.includeAudio()) {
            throw new UnsupportedOperationException("音频截面未实现（W-071）");
        }
        CameraSpec active = CameraManager.get().active();
        boolean oneShot = request.temporary() || request.hasExplicitCamera() || active == null;
        JpegFrame frame;
        if (oneShot) {
            if (active == null && !request.hasExplicitCamera()) {
                throw new IOException("无 active 相机且未提供相机参数");
            }
            try {
                frame = VideoStreamHub.get().requestOneShot(request.toCameraParams(active), request.timeoutMillis())
                        .get(request.timeoutMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("帧获取被中断", e);
            } catch (TimeoutException e) {
                throw new IOException("帧获取超时（" + request.timeoutMillis() + "ms）", e);
            } catch (ExecutionException e) {
                throw new IOException("实时调取失败：" + e.getCause(), e.getCause());
            }
            if (frame == null) {
                throw new IOException("实时调取未返回帧");
            }
        } else {
            long deadline = com.captureapi.core.time.CaptureClock.get().nowNanos()
                    + request.timeoutMillis() * 1_000_000L;
            frame = VideoFrameBus.get().latest();
            while (frame == null && com.captureapi.core.time.CaptureClock.get().nowNanos() < deadline) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待帧被中断", e);
                }
                frame = VideoFrameBus.get().latest();
            }
            if (frame == null) {
                throw new IOException("等待帧超时（无流式帧）");
            }
        }
        if (request.outputMethod() == FrameRequest.OutputMethod.HTTP) {
            NetworkServiceManager service = NetworkServiceManager.get();
            if (!service.isRunning()) {
                throw new IOException("网络服务未运行，无法返回 HTTP URL");
            }
            if (oneShot) {
                long ttlMs = Math.max(1L, CaptureParams.get().frameCacheTtlSec()) * 1000L;
                VideoFrameBus.get().publishTemporary(frame, ttlMs);
            }
            String host = CaptureParams.get().bindLocalOnly() || !CaptureParams.get().lanEnabled()
                    ? "127.0.0.1" : localHost();
            String url = "http://" + host + ":" + service.httpPort() + "/camera/0/frame";
            return FrameResult.http(url, frame.timestampNanos(), frame.width(), frame.height(), frame.jpeg().length);
        }
        byte[] output = request.format() == FrameRequest.Format.PNG ? toPng(frame) : frame.jpeg();
        Path path = request.outputPath();
        if (path == null) {
            Path dir = FMLPaths.GAMEDIR.get().resolve(CaptureParams.get().recordingOutputDir());
            Files.createDirectories(dir);
            String extension = request.format() == FrameRequest.Format.PNG ? ".png" : ".jpg";
            path = dir.resolve("capture_" + frame.timestampNanos() + extension);
        } else if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        path = uniquePath(path);
        Files.write(path, output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return FrameResult.file(path, frame.timestampNanos(), frame.width(), frame.height(), output.length);
    }

    private static Path uniquePath(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String suffix = dot < 0 ? "" : name.substring(dot);
        for (int i = 1; i < MAX_FRAME_POLLS; i++) {
            Path candidate = path.resolveSibling(base + "_" + i + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return path;
    }

    private static byte[] toPng(JpegFrame frame) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.jpeg()));
        if (image == null) {
            throw new IOException("JPEG 解码失败，无法输出 PNG");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(frame.jpeg().length);
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG 编码器不可用");
        }
        return output.toByteArray();
    }

    private static String localHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // ===== 音频 =====

    /** @return 最近写入的音频块（非阻塞、非消费；无数据时 null） */
    public static AudioChunk latestAudioChunk() {
        return AudioRingBuffer.get().peekLatest();
    }

    /**
     * 音频截面（虚拟位置）——**待 W-071 语义冻结/实现**。
     *
     * @return 明确失败 Future（不为假实现）
     */
    public static CompletableFuture<AudioChunk> grabAudioSection(double x, double y, double z, int chunkCount) {
        if (chunkCount < 1 || chunkCount > 100) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("chunkCount 必须在 [1,100]"));
        }
        LOGGER.warn("{} [API] grabAudioSection 尚未实现（W-071），明确失败而非假返回", CaptureConstants.LOG_MARKER);
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("音频截面待 W-071 语义冻结后实现"));
    }

    // ===== 相机管理（代理 CameraManager） =====

    /** 创建相机（任意线程；首个自动 active）。 */
    public static CameraSpec createCamera(CameraCreateParams params) {
        return CameraManager.get().create(params);
    }

    /** 调整相机参数。 */
    public static CameraSpec adjustCamera(int id, CameraCreateParams params) {
        return CameraManager.get().adjust(id, params);
    }

    /** 激活相机。 */
    public static CameraSpec activateCamera(int id) {
        return CameraManager.get().activate(id);
    }

    /** 暂停相机。 */
    public static CameraSpec pauseCamera(int id) {
        return CameraManager.get().pause(id);
    }

    /** 恢复相机。 */
    public static CameraSpec resumeCamera(int id) {
        return CameraManager.get().resume(id);
    }

    /** 删除相机。 */
    public static void deleteCamera(int id) {
        CameraManager.get().delete(id);
    }

    /** @return 相机列表（id 升序副本） */
    public static List<CameraSpec> listCameras() {
        return CameraManager.get().list();
    }

    /** @return 当前 active 相机 id；0=无 */
    public static int activeCameraId() {
        return CameraManager.get().activeCameraId();
    }
}
