package com.captureapi.core.config;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.core.thread.CaptureRenderHook;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.core.time.CaptureClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 参数访问门面（I1.5.4，红线 1 的运行时读取入口）。
 *
 * <p>持有 volatile 不可变快照：所有 getter 从快照读取（热路径无锁）；快照由
 * {@link ModConfigEvents} 在 Loading/Reloading 事件中重建。模块代码只允许经本门面读参数，
 * 禁止直接 {@code ConfigValue#get()}。</p>
 *
 * <p>刷新时同步把诊断开关接入各设施（T1.2/T1.3/T1.4）；M4 网络服务的热重载联动通过
 * {@link #onParamsReloaded(ReloadScope)} 预留。</p>
 */
public final class CaptureParams {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final CaptureParams INSTANCE = new CaptureParams();

    private final CountDownLatch loadedLatch = new CountDownLatch(1);
    private volatile Snapshot snapshot;

    private CaptureParams() {
    }

    /** @return 全局单例门面 */
    public static CaptureParams get() {
        return INSTANCE;
    }

    /**
     * 从 spec 重建快照（仅由配置事件调用）。
     *
     * @param scope 刷新范围（首次加载/重载）
     */
    public void refreshFromSpec(ReloadScope scope) {
        if (!CaptureConfig.SPEC.isLoaded()) {
            LOGGER.warn("{} 配置尚未加载完成，延迟刷新（scope={}）", CaptureConstants.LOG_MARKER, scope);
            return;
        }
        Snapshot next = new Snapshot(
                CaptureConfig.FPS_MAX.get(),
                CaptureConfig.VIDEO_RESOLUTION.get(),
                CaptureConfig.JPEG_QUALITY.get(),
                CaptureConfig.NEAR_PLANE.get(),
                CaptureConfig.FAR_PLANE.get(),
                CaptureConfig.AUDIO_ENABLED.get(),
                CaptureConfig.SAMPLE_RATE.get(),
                CaptureConfig.BIT_DEPTH.get(),
                CaptureConfig.CHANNELS.get(),
                CaptureConfig.NET_PORT.get(),
                CaptureConfig.BIND_LOCAL_ONLY.get(),
                CaptureConfig.LAN_ENABLED.get(),
                CaptureConfig.TOKEN_ENABLED.get(),
                CaptureConfig.LOG_RESOURCE_LIFECYCLE.get(),
                CaptureConfig.LOG_FRAME_EVENTS.get(),
                CaptureConfig.LOG_GL_THREAD_VIOLATION.get(),
                CaptureConfig.CLOCK_LOGGING.get());
        snapshot = next;
        loadedLatch.countDown();
        applyDiagnosticSwitches(next);
        LOGGER.info("{} 参数快照{}：fps_max={}, video_resolution={}, jpeg_quality={}, "
                        + "near_plane={}, far_plane={}, "
                        + "audio_enabled={}, sample_rate={}, bit_depth={}, channels={}, "
                        + "net_port={}, bind_local_only={}, lan_enabled={}, token_enabled={}, "
                        + "log_resource_lifecycle={}, log_frame_events={}, log_gl_thread_violation={}, clock_logging={}",
                CaptureConstants.LOG_MARKER,
                scope == ReloadScope.INITIAL_LOAD ? "建立（Loading）" : "刷新（Reloading）",
                next.fpsMax(), next.videoResolution(), next.jpegQuality(),
                next.nearPlane(), next.farPlane(),
                next.audioEnabled(), next.sampleRate(), next.bitDepth(), next.channels(),
                next.netPort(), next.bindLocalOnly(), next.lanEnabled(), next.tokenEnabled(),
                next.logResourceLifecycle(), next.logFrameEvents(), next.logGlThreadViolation(),
                next.clockLogging());
        onParamsReloaded(scope);
    }

    /**
     * 阻塞等待配置快照就绪（M2~M4 初始化期使用）。
     *
     * @param timeoutMillis 超时毫秒
     * @return 就绪返回 true；超时返回 false
     */
    public boolean awaitLoaded(long timeoutMillis) {
        try {
            return loadedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 阻塞等待配置快照就绪（默认 10s 超时；超时抛异常）。 */
    public void awaitLoaded() {
        if (!awaitLoaded(10_000L)) {
            throw new IllegalStateException("CaptureParams 等待配置快照超时（10s）");
        }
    }

    /** @return 快照是否已建立 */
    public boolean isLoaded() {
        return snapshot != null;
    }

    // ===== 类型化 getter（热路径无锁） =====

    public int fpsMax() {
        return current().fpsMax();
    }

    public String videoResolution() {
        return current().videoResolution();
    }

    public int jpegQuality() {
        return current().jpegQuality();
    }

    /** @return 投影近裁剪距离（T2.1.2） */
    public double nearPlane() {
        return current().nearPlane();
    }

    /** @return 投影远裁剪距离；0=自动（渲染距离×16，T2.1.2） */
    public double farPlane() {
        return current().farPlane();
    }

    public boolean audioEnabled() {
        return current().audioEnabled();
    }

    public int sampleRate() {
        return current().sampleRate();
    }

    public int bitDepth() {
        return current().bitDepth();
    }

    public int channels() {
        return current().channels();
    }

    public int netPort() {
        return current().netPort();
    }

    public boolean bindLocalOnly() {
        return current().bindLocalOnly();
    }

    public boolean lanEnabled() {
        return current().lanEnabled();
    }

    public boolean tokenEnabled() {
        return current().tokenEnabled();
    }

    public boolean logResourceLifecycle() {
        return current().logResourceLifecycle();
    }

    public boolean logFrameEvents() {
        return current().logFrameEvents();
    }

    public boolean logGlThreadViolation() {
        return current().logGlThreadViolation();
    }

    public boolean clockLogging() {
        return current().clockLogging();
    }

    // ===== 内部 =====

    private Snapshot current() {
        Snapshot current = snapshot;
        if (current == null) {
            throw new IllegalStateException("CaptureParams 尚未加载；初始化期请先 awaitLoaded()");
        }
        return current;
    }

    private void applyDiagnosticSwitches(Snapshot values) {
        GlThreadGuard.setViolationLogging(values.logGlThreadViolation());
        CaptureRenderHook.setFrameEventLogging(values.logFrameEvents());
        WorldResourceManager.get().setLifecycleLogging(values.logResourceLifecycle());
        CaptureClock.get().setLogging(values.clockLogging());
    }

    /** M4 网络服务热重载联动钩子（M1 空实现；M4 细化时填充）。 */
    private void onParamsReloaded(ReloadScope scope) {
        // 预留：M4 网络服务在 Reloading 时按策略联动（端口/绑定类参数需重启服务，见附录 B 生效时机）。
    }

    /**
     * 参数快照（不可变；字段与 M1 配置骨架一致）。
     */
    public record Snapshot(
            int fpsMax,
            String videoResolution,
            int jpegQuality,
            double nearPlane,
            double farPlane,
            boolean audioEnabled,
            int sampleRate,
            int bitDepth,
            int channels,
            int netPort,
            boolean bindLocalOnly,
            boolean lanEnabled,
            boolean tokenEnabled,
            boolean logResourceLifecycle,
            boolean logFrameEvents,
            boolean logGlThreadViolation,
            boolean clockLogging) {
    }
}
