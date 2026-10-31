package com.captureapi.video.encode;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.frame.RawFrame;
import com.captureapi.video.filter.FilterRegistry;
import com.captureapi.video.frame.VideoFrameBus;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编码线程归属与帧发布（I2.5.2）。
 *
 * <p>默认渲染线程内联编码（3~6ms 预估预算内）；{@code encode_async=true} 时渲染线程把
 * {@link RawFrame} 放入有界队列（容量 {@link CaptureConstants#ENCODE_QUEUE_CAPACITY}），
 * 独立线程 {@code captureapi-encoder} 消费编码并发布。两种模式发布端对象与顺序语义一致。</p>
 *
 * <p>队列满策略：丢最旧、保最新（计数）；编码失败隔离并计数，不中断流水线。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class EncodingPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final EncodingPipeline INSTANCE = new EncodingPipeline();

    private final JpegEncoder encoder = new JpegEncoder();
    private final AtomicLong encodedFrames = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private final AtomicLong failedFrames = new AtomicLong();
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private volatile ArrayBlockingQueue<RawFrame> queue;
    private volatile Thread worker;

    private EncodingPipeline() {
    }

    public static EncodingPipeline get() {
        return INSTANCE;
    }

    /**
     * 接收一帧：异步模式入队，内联模式立即编码发布。
     *
     * @param frame 原始帧（不可变堆数据）
     */
    public void accept(RawFrame frame) {
        if (CaptureParams.get().encodeAsync()) {
            ensureWorker();
            ArrayBlockingQueue<RawFrame> current = queue;
            if (current == null) {
                encodeAndPublish(frame);
                return;
            }
            if (!current.offer(frame)) {
                current.poll(); // 丢最旧
                if (!current.offer(frame)) {
                    droppedFrames.incrementAndGet();
                    return;
                }
                droppedFrames.incrementAndGet();
            }
        } else {
            encodeAndPublish(frame);
        }
    }

    private void encodeAndPublish(RawFrame frame) {
        try {
            // T8.1：编码前应用当前滤镜（无滤镜/失败则原帧；单滤镜失败不影响链路）
            RawFrame filtered = FilterRegistry.get().applySelected(frame);
            byte[] jpeg = encoder.encode(filtered, CaptureParams.get().jpegQuality());
            VideoFrameBus.get().publish(new JpegFrame(jpeg, filtered.width(), filtered.height(),
                    filtered.timestampNanos()));
            encodedFrames.incrementAndGet();
        } catch (Throwable t) {
            failedFrames.incrementAndGet();
            LOGGER.warn("{} JPEG 编码失败（已隔离，丢弃该帧）", CaptureConstants.LOG_MARKER, t);
        }
    }

    private void ensureWorker() {
        if (worker != null) {
            return;
        }
        synchronized (this) {
            if (worker != null) {
                return;
            }
            queue = new ArrayBlockingQueue<>(CaptureConstants.ENCODE_QUEUE_CAPACITY);
            Thread thread = new Thread(this::workerLoop, CaptureConstants.THREAD_ENCODER);
            thread.setDaemon(true);
            worker = thread;
            workerRunning.set(true);
            thread.start();
            LOGGER.info("{} 异步编码线程启动：{}（队列容量={}）", CaptureConstants.LOG_MARKER,
                    CaptureConstants.THREAD_ENCODER, CaptureConstants.ENCODE_QUEUE_CAPACITY);
        }
    }

    private void workerLoop() {
        ArrayBlockingQueue<RawFrame> current = queue;
        while (workerRunning.get() && current != null) {
            try {
                RawFrame frame = current.take();
                encodeAndPublish(frame);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                failedFrames.incrementAndGet();
                LOGGER.warn("{} 异步编码循环异常（继续）", CaptureConstants.LOG_MARKER, t);
            }
        }
    }

    /** @return 累计成功编码发布帧数 */
    public long encodedFrames() {
        return encodedFrames.get();
    }

    /** @return 异步模式累计因队列满丢弃的帧数 */
    public long droppedFrames() {
        return droppedFrames.get();
    }

    /** @return 编码失败帧数 */
    public long failedFrames() {
        return failedFrames.get();
    }

    /** 停用编码线程（客户端退出/测试）。 */
    public void shutdown() {
        workerRunning.set(false);
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
            worker = null;
        }
        ArrayBlockingQueue<RawFrame> current = queue;
        if (current != null) {
            current.clear();
        }
    }
}
