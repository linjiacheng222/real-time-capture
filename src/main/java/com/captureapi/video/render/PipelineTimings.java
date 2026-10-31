package com.captureapi.video.render;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帧流水线分段耗时统计（T2.5.3）。
 *
 * <p>记录离屏渲染段 / PBO 回读段 / 编码段 / 全帧耗时（CaptureClock 差值），
 * {@code log_pipeline_timing=true} 时每 300 帧输出一次均值。用于 MS2 预算判定
 * （30fps 下渲染线程总占用 < 帧间隔 33ms 的 70%≈23ms）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PipelineTimings {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private static final AtomicLong FRAMES = new AtomicLong();
    private static final AtomicLong RENDER_NANOS = new AtomicLong();
    private static final AtomicLong READBACK_NANOS = new AtomicLong();
    private static final AtomicLong ENCODE_NANOS = new AtomicLong();

    private PipelineTimings() {
        throw new AssertionError("no instances");
    }

    /**
     * 记录一帧的分段耗时。
     *
     * @param renderNanos   离屏渲染段
     * @param readbackNanos PBO 回读段
     * @param encodeNanos   编码段（异步模式为入队耗时）
     */
    public static void record(long renderNanos, long readbackNanos, long encodeNanos) {
        long frames = FRAMES.incrementAndGet();
        RENDER_NANOS.addAndGet(renderNanos);
        READBACK_NANOS.addAndGet(readbackNanos);
        ENCODE_NANOS.addAndGet(encodeNanos);
        if (CaptureParams.get().logPipelineTiming() && frames % 300L == 0L) {
            LOGGER.info("{} 流水线耗时均值（{}帧）：render={}ms，readback={}ms，encode={}ms，total={}ms",
                    CaptureConstants.LOG_MARKER, frames,
                    RENDER_NANOS.get() / (double) frames / 1_000_000.0D,
                    READBACK_NANOS.get() / (double) frames / 1_000_000.0D,
                    ENCODE_NANOS.get() / (double) frames / 1_000_000.0D,
                    (RENDER_NANOS.get() + READBACK_NANOS.get() + ENCODE_NANOS.get()) / (double) frames / 1_000_000.0D);
        }
    }

    public static long frames() {
        return FRAMES.get();
    }
}
