package com.captureapi.video.frame;

import com.captureapi.core.thread.LatestValueChannel;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 最新 JPEG 帧总线（I2.5.2）。
 *
 * <p>M2 生产、M4 唯一消费入口；语义为「仅保留最新一帧（保新丢旧）」，慢消费者永不阻塞生产。
 * 消费方通过 {@link JpegFrame#timestampNanos()} 判断新鲜度；暂停时旧帧保留但可被识别。</p>
 */
public final class VideoFrameBus {

    private static final VideoFrameBus INSTANCE = new VideoFrameBus();

    private final LatestValueChannel<JpegFrame> channel = new LatestValueChannel<>();
    private final AtomicLong published = new AtomicLong();

    private VideoFrameBus() {
    }

    public static VideoFrameBus get() {
        return INSTANCE;
    }

    /** 发布最新帧（M2 编码完成时调用；覆盖旧值）。 */
    public void publish(JpegFrame frame) {
        channel.publish(frame);
        published.incrementAndGet();
    }

    /** @return 最新帧；从未发布或已清空时 null */
    public JpegFrame latest() {
        return channel.latest();
    }

    /** @return 累计发布次数 */
    public long publishedCount() {
        return published.get();
    }

    /** 清空（世界卸载/测试）。 */
    public void clear() {
        channel.clear();
    }
}
