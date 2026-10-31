package com.captureapi.audio.sync;

import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 音画同步偏差监测（T5.3 / I5.3.1~3）。
 *
 * <p>核心指标：音频区间中点 − 相邻视频帧时间戳的滑动均值（音画交叉差），恒定即相对节奏正确。
 * 辅助：时间戳回退计数、告警计数。开关 {@code sync_monitor_enabled} 默认关（零日志开销）；
 * 超 {@code warn_threshold_ms} 打 WARN；分钟级趋势行用于长跑判读。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SyncDeviationMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final SyncDeviationMonitor INSTANCE = new SyncDeviationMonitor();
    private static final long MINUTE_NANOS = 60_000_000_000L;

    private final AtomicLong videoSamples = new AtomicLong();
    private final AtomicLong audioSamples = new AtomicLong();
    private final AtomicLong regressions = new AtomicLong();
    private final AtomicLong warnings = new AtomicLong();
    private final AtomicLong deltaSamples = new AtomicLong();

    private volatile long lastVideoTimestamp;
    private volatile long lastAudioMidpoint;
    private volatile double averageDeltaMs;
    private volatile double lastDeltaMs;
    private volatile double minuteMinMs = Double.NaN;
    private volatile double minuteMaxMs = Double.NaN;

    private long lastSampleLogNanos;
    private long minuteStartNanos;
    private long minuteWarningBase;

    private SyncDeviationMonitor() {
    }

    public static SyncDeviationMonitor get() {
        return INSTANCE;
    }

    /** 视频帧时间戳（回读完成时刻）。 */
    public void onVideo(long timestampNanos) {
        long previous = lastVideoTimestamp;
        if (previous != 0L && timestampNanos < previous) {
            regressions.incrementAndGet();
        }
        lastVideoTimestamp = timestampNanos;
        videoSamples.incrementAndGet();
        updateDelta();
    }

    /** 音频块区间（起始/终点）。 */
    public void onAudio(AudioChunk chunk) {
        long midpoint = chunk.startNanos() + (chunk.endNanos() - chunk.startNanos()) / 2L;
        long previous = lastAudioMidpoint;
        if (previous != 0L && midpoint < previous) {
            regressions.incrementAndGet();
        }
        lastAudioMidpoint = midpoint;
        audioSamples.incrementAndGet();
        updateDelta();
    }

    private void updateDelta() {
        long video = lastVideoTimestamp;
        long audio = lastAudioMidpoint;
        if (video == 0L || audio == 0L) {
            return;
        }
        double deltaMs = (audio - video) / 1_000_000.0D;
        lastDeltaMs = deltaMs;
        long samples = deltaSamples.incrementAndGet();
        averageDeltaMs += (deltaMs - averageDeltaMs) / Math.min(samples, 100L);
        if (Double.isNaN(minuteMinMs) || deltaMs < minuteMinMs) {
            minuteMinMs = deltaMs;
        }
        if (Double.isNaN(minuteMaxMs) || deltaMs > minuteMaxMs) {
            minuteMaxMs = deltaMs;
        }
        long now = com.captureapi.core.time.CaptureClock.get().nowNanos();
        if (minuteStartNanos == 0L) {
            minuteStartNanos = now;
            minuteWarningBase = warnings.get();
        }
        if (!CaptureParams.get().syncMonitorEnabled()) {
            return;
        }
        if (now - lastSampleLogNanos >= 1_000_000_000L) {
            lastSampleLogNanos = now;
            LOGGER.info("{} [SYNC] delta_ms={} avg_ms={} samples={} regressions={}",
                    CaptureConstants.LOG_MARKER, round(deltaMs), round(averageDeltaMs), deltaSamples.get(),
                    regressions.get());
        }
        if (Math.abs(deltaMs) > CaptureParams.get().warnThresholdMs()) {
            warnings.incrementAndGet();
            LOGGER.warn("{} [SYNC/WARN] 音画偏差超阈值：delta_ms={} threshold_ms={}",
                    CaptureConstants.LOG_MARKER, round(deltaMs), CaptureParams.get().warnThresholdMs());
        }
        if (now - minuteStartNanos >= MINUTE_NANOS) {
            long warningDelta = warnings.get() - minuteWarningBase;
            LOGGER.info("{} [SYNC/TREND] 1min delta_ms[min/avg/max]={}/{}/{} warnings={}",
                    CaptureConstants.LOG_MARKER, round(minuteMinMs), round(averageDeltaMs), round(minuteMaxMs),
                    warningDelta);
            minuteStartNanos = now;
            minuteMinMs = Double.NaN;
            minuteMaxMs = Double.NaN;
            minuteWarningBase = warnings.get();
        }
    }

    private static String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    public double averageDeltaMs() {
        return averageDeltaMs;
    }

    public double lastDeltaMs() {
        return lastDeltaMs;
    }

    public long deltaSamples() {
        return deltaSamples.get();
    }

    public long videoSamples() {
        return videoSamples.get();
    }

    public long audioSamples() {
        return audioSamples.get();
    }

    public long regressions() {
        return regressions.get();
    }

    public long warnings() {
        return warnings.get();
    }
}
