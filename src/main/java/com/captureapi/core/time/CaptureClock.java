package com.captureapi.core.time;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.ManagedResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全模组唯一时间源（I1.4.1，红线 4）。
 *
 * <p>{@link #nowNanos()} 是唯一取时入口，内部 {@code System.nanoTime()}（JVM 保证单调，仅用于
 * 差值与相对对齐，不做跨进程绝对时间换算）。三类时间戳（视频帧/音频块/协议字段）必须取自本实例，
 * 才能直接对齐（M5 对齐规则的前提）。</p>
 *
 * <p>打标约定（M2/M3 直接调用本类）：视频帧＝PBO 读取到像素的时刻；音频块＝读取起始时刻。</p>
 *
 * <p>时钟本身作为全局级资源（T1.2 同构）注册于 {@code WorldResourceManager}，随客户端生命周期。</p>
 */
public final class CaptureClock implements ManagedResource {

    /** 资源键（全局级；T1.2 资源管理器登记）。 */
    public static final String KEY = CaptureConstants.RESOURCE_KEY_PREFIX + "core:clock";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final CaptureClock INSTANCE = new CaptureClock();

    /** 配置项 clock_logging（默认 false；T1.5 接入）。 */
    private volatile boolean logging;

    private CaptureClock() {
    }

    /** @return 全模组唯一时钟实例 */
    public static CaptureClock get() {
        return INSTANCE;
    }

    /**
     * @return 当前纳秒时间戳（唯一取时入口；任何线程可调用）
     */
    public long nowNanos() {
        return System.nanoTime();
    }

    /**
     * 纳秒→毫秒换算（仅日志展示用；禁止用于音画时间戳语义）。
     *
     * @param nanos {@link #nowNanos()} 或时间差
     * @return 毫秒字面值
     */
    public long toMillisForLog(long nanos) {
        return nanos / CaptureConstants.NANOS_PER_MILLI;
    }

    /**
     * 纳秒→秒换算（仅日志展示用）。
     *
     * @param nanos 纳秒时长
     * @return 秒（double）
     */
    public double toSecondsForLog(long nanos) {
        return nanos / (double) CaptureConstants.NANOS_PER_SECOND;
    }

    /**
     * 单调性自检：连续采样 {@code samples} 次，要求时间戳非递减且能够测到时间前进。
     *
     * @param samples 采样次数（≥2）
     * @return 通过返回 true
     */
    public boolean selfTestMonotonic(int samples) {
        int count = Math.max(samples, 2);
        long prev = nowNanos();
        long first = prev;
        for (int i = 1; i < count; i++) {
            long now = nowNanos();
            if (now < prev) {
                LOGGER.error("{} 时钟单调性失败：prev={} now={}", CaptureConstants.LOG_MARKER, prev, now);
                return false;
            }
            prev = now;
        }
        boolean advanced = prev > first;
        if (!advanced) {
            LOGGER.warn("{} 时钟自检：{} 次采样未测到前进（分辨率/负载原因，记录待观察）", CaptureConstants.LOG_MARKER, count);
        }
        return true;
    }

    /** 接入配置项 clock_logging（T1.5；默认 false）。 */
    public void setLogging(boolean enabled) {
        this.logging = enabled;
    }

    /** @return 当前是否开启时钟诊断日志 */
    public boolean isLogging() {
        return logging;
    }

    // ===== 全局级资源（T1.2 同构）=====

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public void create() {
        LOGGER.debug("{} 全局资源创建：{}（唯一时间源就绪）", CaptureConstants.LOG_MARKER, KEY);
    }

    @Override
    public void destroy() {
        LOGGER.debug("{} 全局资源销毁：{}", CaptureConstants.LOG_MARKER, KEY);
    }
}
