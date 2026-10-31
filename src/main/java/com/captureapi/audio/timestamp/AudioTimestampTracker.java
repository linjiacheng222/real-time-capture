package com.captureapi.audio.timestamp;

import com.captureapi.core.constant.CaptureConstants;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 音频块时间连续性/漂移监测（I3.4.3）。
 *
 * <p>写端记录前块终点：新块起始应等于前块终点，不等则计不连续（丢拍/静音/重同步的直接证据）；
 * 每 100 块采样“终点 vs CaptureClock 实时值”的漂移值，暴露给 M5/T5.3 与长跑监控。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AudioTimestampTracker {

    private static final AudioTimestampTracker INSTANCE = new AudioTimestampTracker();

    private final AtomicLong chunks = new AtomicLong();
    private final AtomicLong discontinuities = new AtomicLong();
    private volatile long previousEndNanos;
    private volatile long lastDriftNanos;
    private volatile long maxDriftNanos;

    private AudioTimestampTracker() {
    }

    public static AudioTimestampTracker get() {
        return INSTANCE;
    }

    /**
     * 记录一块。
     *
     * @param startNanos 起始
     * @param endNanos   终点
     * @param nowNanos   当前时钟（用于漂移采样）
     */
    public synchronized void onChunk(long startNanos, long endNanos, long nowNanos) {
        long count = chunks.incrementAndGet();
        if (previousEndNanos != 0L && startNanos != previousEndNanos) {
            discontinuities.incrementAndGet();
        }
        previousEndNanos = endNanos;
        if (count % 100L == 0L) {
            // 正值=音频时间轴落后实时（漂移趋势）；仅记录趋势，不参与推算
            long drift = nowNanos - endNanos;
            lastDriftNanos = drift;
            if (Math.abs(drift) > Math.abs(maxDriftNanos)) {
                maxDriftNanos = drift;
            }
        }
    }

    public long chunks() {
        return chunks.get();
    }

    public long discontinuities() {
        return discontinuities.get();
    }

    public long lastDriftNanos() {
        return lastDriftNanos;
    }

    public long maxDriftNanos() {
        return maxDriftNanos;
    }

    /** 重置（测试/重新开始采集）。 */
    public synchronized void reset() {
        chunks.set(0L);
        discontinuities.set(0L);
        previousEndNanos = 0L;
        lastDriftNanos = 0L;
        maxDriftNanos = 0L;
    }
}
