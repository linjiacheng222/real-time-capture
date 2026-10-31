package com.captureapi.video.stream;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.video.camera.CameraCreateParams;
import com.captureapi.video.frame.JpegFrame;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实时调取入口（I2.2.4）。
 *
 * <p>任意线程提交 {@link #requestOneShot(CameraCreateParams, long)}，渲染线程在下一个
 * AFTER_LEVEL 检出请求，走与流式相同的渲染/回读/编码流程（同帧互斥：有请求时本帧不跑流式），
 * 完成后以 {@link JpegFrame} 完成 Future。临时相机不进 CameraManager、不占 id。</p>
 *
 * <p>队列容量 {@link CaptureConstants#ONESHOT_QUEUE_CAPACITY}（结构防积压）；世界未就绪直接失败；
 * 超时在下一次轮询时异常完成。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class VideoStreamHub {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final VideoStreamHub INSTANCE = new VideoStreamHub();

    private final ConcurrentLinkedQueue<OneShotRequest> queue = new ConcurrentLinkedQueue<>();

    private VideoStreamHub() {
    }

    public static VideoStreamHub get() {
        return INSTANCE;
    }

    /**
     * 提交一次实时调取（任意线程）。
     *
     * @param params        临时相机参数
     * @param timeoutMillis 超时毫秒（调用方指定；<=0 按 1ms）
     * @return JPEG 帧 Future（失败/超时异常完成）
     */
    public CompletableFuture<JpegFrame> requestOneShot(CameraCreateParams params, long timeoutMillis) {
        CompletableFuture<JpegFrame> future = new CompletableFuture<>();
        if (params == null) {
            future.completeExceptionally(new IllegalArgumentException("相机参数不可为 null"));
            return future;
        }
        if (!WorldResourceManager.get().isWorldReady()) {
            future.completeExceptionally(new IllegalStateException("世界未就绪，无法实时调取"));
            return future;
        }
        if (queue.size() >= CaptureConstants.ONESHOT_QUEUE_CAPACITY) {
            future.completeExceptionally(new RejectedExecutionException(
                    "实时调取队列已满（容量=" + CaptureConstants.ONESHOT_QUEUE_CAPACITY + "）"));
            return future;
        }
        long timeout = Math.max(1L, timeoutMillis);
        long deadline = CaptureClock.get().nowNanos() + timeout * 1_000_000L;
        queue.add(new OneShotRequest(params, future, deadline));
        return future;
    }

    /**
     * 渲染线程轮询一个待处理请求（过期请求在此异常完成）。
     *
     * @return 待处理请求；无则 null
     */
    public OneShotRequest pollPending() {
        OneShotRequest request;
        long now = CaptureClock.get().nowNanos();
        while ((request = queue.poll()) != null) {
            if (request.isExpired(now)) {
                request.fail(new TimeoutException("实时调取超时（未能在截止前渲染）"));
                LOGGER.warn("{} 实时调取超时，已异常完成", CaptureConstants.LOG_MARKER);
            } else {
                return request;
            }
        }
        return null;
    }

    /** @return 队列中待处理数量（诊断） */
    public int queuedRequests() {
        return queue.size();
    }

    /** 清空队列并异常完成（世界卸载/测试）。 */
    public void clear() {
        OneShotRequest request;
        while ((request = queue.poll()) != null) {
            request.fail(new IllegalStateException("实时调取已取消（队列清空）"));
        }
    }
}
