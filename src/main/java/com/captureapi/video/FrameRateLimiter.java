package com.captureapi.video;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.config.CaptureParams;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离屏渲染帧率限流器（I2.2.1）。
 *
 * <p>在 AFTER_LEVEL 入口判定本游戏帧是否执行一次离屏渲染；与游戏原生帧率解耦（cap 不是保证：
 * 游戏帧率低于 fps_max 时离屏帧率随游戏帧封顶）。仅渲染主线程调用；fps_max 每次判定热读，
 * 配置热改即时生效。</p>
 *
 * <p>丢帧计数进 debug 日志（{@code log_frame_drops=true} 时每 300 次丢帧输出一次）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FrameRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private final AtomicLong allowedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();
    private long nextAllowedNanos;
    private int configuredFpsMax;

    /**
     * 判定本次 AFTER_LEVEL 是否放行离屏渲染。
     *
     * @param nowNanos 本次回调时刻（必须来自 CaptureClock.nowNanos，唯一时间源）
     * @return true=本帧执行离屏渲染
     */
    public boolean allow(long nowNanos) {
        int fpsMax = CaptureParams.get().videoFpsMax();
        if (fpsMax <= 0) {
            fpsMax = 1;
        }
        long intervalNanos = CaptureConstants.NANOS_PER_SECOND / fpsMax;
        if (nextAllowedNanos == 0L || configuredFpsMax != fpsMax) {
            // 首次/配置变更：从当前帧开始建立固定网格
            configuredFpsMax = fpsMax;
            nextAllowedNanos = nowNanos + intervalNanos;
            allowedCount.incrementAndGet();
            return true;
        }
        if (nowNanos >= nextAllowedNanos) {
            // 固定网格累加，避免“last=now”抖动累积导致实际帧率低于目标
            nextAllowedNanos += intervalNanos;
            if (nowNanos - nextAllowedNanos > intervalNanos * 4L) {
                // 长时间停顿后重新对齐，避免追赶风暴
                nextAllowedNanos = nowNanos + intervalNanos;
            }
            allowedCount.incrementAndGet();
            return true;
        }
        long dropped = droppedCount.incrementAndGet();
        if (CaptureParams.get().logFrameDrops() && dropped % 300L == 0L) {
            LOGGER.info("{} 离屏限流丢帧统计：已放行={}，已丢弃={}，fps_max={}",
                    CaptureConstants.LOG_MARKER, allowedCount.get(), dropped, fpsMax);
        }
        return false;
    }

    /** @return 累计放行次数 */
    public long allowedCount() {
        return allowedCount.get();
    }

    /** @return 累计丢弃次数 */
    public long droppedCount() {
        return droppedCount.get();
    }

    /** 重置（相机切换/测试用）。 */
    public void reset() {
        nextAllowedNanos = 0L;
        configuredFpsMax = 0;
        allowedCount.set(0L);
        droppedCount.set(0L);
    }
}
