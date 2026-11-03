package com.captureapi.video.camera;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.video.frame.JpegFrame;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 多相机最新帧缓存（P2.3 v1）。
 *
 * <p>单相机 active 流仍走 {@link VideoFrameBus}；非 active 相机由
 * {@link MultiCameraCaptureService} 以时间片方式独立渲染并放入本缓存，HTTP/Java 可按 id 读取。
 * 当前为独立输出 v1：共享 FBO/PBO 顺序渲染，尚未做到每相机独立 FBO/PBO 并行渲染。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CameraFrameStore {

    private static final Map<Integer, JpegFrame> FRAMES = new ConcurrentHashMap<>();
    private static final Map<Integer, List<CompletableFuture<JpegFrame>>> WAITERS =
            new ConcurrentHashMap<>();
    private static final Map<Integer, Long> CONSUMERS = new ConcurrentHashMap<>();

    private CameraFrameStore() {
        throw new AssertionError("no instances");
    }

    /** 写入指定相机最新帧。 */
    public static void put(int cameraId, JpegFrame frame) {
        if (cameraId > 0 && frame != null) {
            FRAMES.put(cameraId, frame);
            List<CompletableFuture<JpegFrame>> waiters = WAITERS.remove(cameraId);
            if (waiters != null) {
                for (CompletableFuture<JpegFrame> waiter : waiters) {
                    waiter.complete(frame);
                }
            }
        }
    }

    /**
     * 等待指定相机最新帧；已有则立即完成（HTTP/内部读取）。
     */
    public static CompletableFuture<JpegFrame> awaitFrame(int cameraId, long timeoutMillis) {
        markConsumer(cameraId);
        JpegFrame latest = FRAMES.get(cameraId);
        if (latest != null) {
            return CompletableFuture.completedFuture(latest);
        }
        return awaitNextFrame(cameraId, timeoutMillis);
    }

    /**
     * 等待指定相机的下一次渲染帧（V3 grabFrame / V6 filter 生效）。
     */
    public static CompletableFuture<JpegFrame> awaitNextFrame(int cameraId, long timeoutMillis) {
        markConsumer(cameraId);
        CompletableFuture<JpegFrame> future = new CompletableFuture<>();
        WAITERS.computeIfAbsent(cameraId, id -> new ArrayList<>()).add(future);
        future.orTimeout(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS)
                .whenComplete((frame, error) -> {
                    List<CompletableFuture<JpegFrame>> waiters = WAITERS.get(cameraId);
                    if (waiters != null) {
                        waiters.remove(future);
                    }
                });
        return future;
    }

    /** @return 指定相机最新帧；无则 null */
    public static JpegFrame get(int cameraId) {
        markConsumer(cameraId);
        return FRAMES.get(cameraId);
    }

    /** 标记该相机近期有消费者（懒渲染信号）。 */
    public static void markConsumer(int cameraId) {
        if (cameraId > 0) {
            CONSUMERS.put(cameraId, CaptureClock.get().nowNanos());
        }
    }

    /** @return 指定相机是否有活跃消费者 */
    public static boolean hasConsumer(int cameraId, long nowNanos) {
        Long last = CONSUMERS.get(cameraId);
        return last != null && nowNanos - last
                < CaptureConstants.MULTI_CAMERA_CONSUMER_TTL_MS * CaptureConstants.NANOS_PER_MILLI;
    }

    /** @return 活跃消费者相机数 */
    public static int consumerCount(long nowNanos) {
        int count = 0;
        for (Integer cameraId : CONSUMERS.keySet()) {
            if (hasConsumer(cameraId, nowNanos)) {
                count++;
            }
        }
        return count;
    }

    /** @return 是否已有指定相机帧 */
    public static boolean has(int cameraId) {
        return FRAMES.containsKey(cameraId);
    }

    /** 删除指定相机帧（相机删除/暂停）。 */
    public static void remove(int cameraId) {
        FRAMES.remove(cameraId);
        CONSUMERS.remove(cameraId);
    }

    /** 仅保留给定相机 id 的帧（清理已删除相机）。 */
    public static void retainOnly(Collection<Integer> cameraIds) {
        FRAMES.keySet().retainAll(cameraIds);
    }

    /** 清空（世界退出/测试）。 */
    public static void clear() {
        FRAMES.clear();
        CONSUMERS.clear();
    }

    /** @return 当前缓存相机数 */
    public static int size() {
        return FRAMES.size();
    }
}
