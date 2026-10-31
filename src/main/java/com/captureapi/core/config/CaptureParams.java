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
                CaptureConfig.ENCODE_ASYNC.get(),
                CaptureConfig.NEAR_PLANE.get(),
                CaptureConfig.FAR_PLANE.get(),
                CaptureConfig.SOUND_RANGE.get(),
                CaptureConfig.CHUNK_RESCAN_MS.get(),
                CaptureConfig.AUDIO_ENABLED.get(),
                CaptureConfig.SAMPLE_RATE.get(),
                CaptureConfig.BIT_DEPTH.get(),
                CaptureConfig.CHANNELS.get(),
                CaptureConfig.POLL_INTERVAL_MS.get(),
                CaptureConfig.THREAD_PRIORITY.get(),
                CaptureConfig.RING_CAPACITY_SEC.get(),
                CaptureConfig.NET_PORT.get(),
                CaptureConfig.BIND_LOCAL_ONLY.get(),
                CaptureConfig.LAN_ENABLED.get(),
                CaptureConfig.TOKEN_ENABLED.get(),
                CaptureConfig.MAX_CONNECTIONS.get(),
                CaptureConfig.MAX_QPS.get(),
                CaptureConfig.FRAME_CACHE_TTL_SEC.get(),
                CaptureConfig.RECORDING_OUTPUT_DIR.get(),
                CaptureConfig.FILTER_DIRECTORY.get(),
                CaptureConfig.FFMPEG_PATH.get(),
                CaptureConfig.RECORDING_FORMAT.get(),
                CaptureConfig.SYNC_MONITOR_ENABLED.get(),
                CaptureConfig.WARN_THRESHOLD_MS.get(),
                CaptureConfig.PERSIST_CHANGES.get(),
                CaptureConfig.LOG_RESOURCE_LIFECYCLE.get(),
                CaptureConfig.LOG_FRAME_EVENTS.get(),
                CaptureConfig.LOG_FRAME_DROPS.get(),
                CaptureConfig.LOG_PIPELINE_TIMING.get(),
                CaptureConfig.LOG_BUFFER_WATERMARK.get(),
                CaptureConfig.LOG_GL_THREAD_VIOLATION.get(),
                CaptureConfig.CLOCK_LOGGING.get());
        snapshot = next;
        loadedLatch.countDown();
        applyDiagnosticSwitches(next);
        LOGGER.info("{} 参数快照{}：fps_max={}, video_resolution={}, jpeg_quality={}, encode_async={}, "
                        + "near_plane={}, far_plane={}, sound_range={}, chunk_rescan_ms={}, "
                        + "audio_enabled={}, sample_rate={}, bit_depth={}, channels={}, "
                        + "poll_interval_ms={}, thread_priority={}, ring_capacity_sec={}, "
                        + "net_port={}, bind_local_only={}, lan_enabled={}, token_enabled={}, "
                        + "max_connections={}, max_qps={}, frame_cache_ttl_sec={}, recording_output_dir={}, "
                        + "log_resource_lifecycle={}, log_frame_events={}, log_frame_drops={}, "
                        + "log_pipeline_timing={}, log_buffer_watermark={}, log_gl_thread_violation={}, "
                        + "clock_logging={}",
                CaptureConstants.LOG_MARKER,
                scope == ReloadScope.INITIAL_LOAD ? "建立（Loading）" : "刷新（Reloading）",
                next.fpsMax(), next.videoResolution(), next.jpegQuality(), next.encodeAsync(),
                next.nearPlane(), next.farPlane(), next.soundRange(), next.chunkRescanMs(),
                next.audioEnabled(), next.sampleRate(), next.bitDepth(), next.channels(),
                next.pollIntervalMs(), next.threadPriority(), next.ringCapacitySec(),
                next.netPort(), next.bindLocalOnly(), next.lanEnabled(), next.tokenEnabled(),
                next.maxConnections(), next.maxQps(), next.frameCacheTtlSec(), next.recordingOutputDir(),
                next.filterDirectory(), next.ffmpegPath(), next.recordingFormat(),
                next.syncMonitorEnabled(), next.warnThresholdMs(), next.persistChanges(),
                next.logResourceLifecycle(), next.logFrameEvents(), next.logFrameDrops(),
                next.logPipelineTiming(), next.logBufferWatermark(), next.logGlThreadViolation(),
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

    /** @return 是否启用异步编码线程（T2.5.2） */
    public boolean encodeAsync() {
        return current().encodeAsync();
    }

    /** @return 是否输出流水线分段耗时（T2.5.3） */
    public boolean logPipelineTiming() {
        return current().logPipelineTiming();
    }

    /** @return 投影近裁剪距离（T2.1.2） */
    public double nearPlane() {
        return current().nearPlane();
    }

    /** @return 投影远裁剪距离；0=自动（渲染距离×16，T2.1.2） */
    public double farPlane() {
        return current().farPlane();
    }

    /** @return 相机可听半径（格，T2.7.1） */
    public int soundRange() {
        return current().soundRange();
    }

    /** @return 摄制区块集合重扫周期（毫秒，T2.7.1） */
    public int chunkRescanMs() {
        return current().chunkRescanMs();
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

    /** @return 环回采集轮询间隔（毫秒，T3.1.4） */
    public int pollIntervalMs() {
        return current().pollIntervalMs();
    }

    /** @return 采集线程优先级（1~10，T3.1.3） */
    public int threadPriority() {
        return current().threadPriority();
    }

    /** @return 环形音频缓冲容量（秒，T3.2.3） */
    public double ringCapacitySec() {
        return current().ringCapacitySec();
    }

    /** @return 是否输出音频缓冲水位（T3.2.3） */
    public boolean logBufferWatermark() {
        return current().logBufferWatermark();
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

    /** @return WS 并发连接上限（T4.1.4） */
    public int maxConnections() {
        return current().maxConnections();
    }

    /** @return HTTP 单帧轮询限速（QPS，T4.5.3） */
    public int maxQps() {
        return current().maxQps();
    }

    /** @return 临时相机帧缓存 TTL（秒，T4.9.3） */
    public int frameCacheTtlSec() {
        return current().frameCacheTtlSec();
    }

    /** @return 帧/录像默认输出目录（相对游戏目录） */
    public String recordingOutputDir() {
        return current().recordingOutputDir();
    }

    /** @return 滤镜目录（相对游戏目录，T8.1） */
    public String filterDirectory() {
        return current().filterDirectory();
    }

    /** @return FFmpeg 可执行路径（T4.10） */
    public String ffmpegPath() {
        return current().ffmpegPath();
    }

    /** @return 录像容器格式（T4.10） */
    public String recordingFormat() {
        return current().recordingFormat();
    }

    /** @return 音画同步偏差监测开关（T5.3） */
    public boolean syncMonitorEnabled() {
        return current().syncMonitorEnabled();
    }

    /** @return 音画同步告警阈值（毫秒，T5.3） */
    public int warnThresholdMs() {
        return current().warnThresholdMs();
    }

    /** @return 运行时改参是否写回 TOML（T6.3.2） */
    public boolean persistChanges() {
        return current().persistChanges();
    }

    /** 运行时改参后立即重建快照（T6.3.3 变更通知/生效）。 */
    public void reloadForRuntimeChange() {
        refreshFromSpec(ReloadScope.RELOAD);
    }

    public boolean logResourceLifecycle() {
        return current().logResourceLifecycle();
    }

    public boolean logFrameEvents() {
        return current().logFrameEvents();
    }

    /** @return 是否输出离屏丢帧统计（T2.2.1） */
    public boolean logFrameDrops() {
        return current().logFrameDrops();
    }

    /** @return 视频帧率上限（T2.2.1 别名；等价 fpsMax()） */
    public int videoFpsMax() {
        return current().fpsMax();
    }

    /** @return 离屏渲染宽（解析 video_resolution，失败回退 1280） */
    public int videoWidth() {
        return parseResolution(current().videoResolution())[0];
    }

    /** @return 离屏渲染高（解析 video_resolution，失败回退 1024） */
    public int videoHeight() {
        return parseResolution(current().videoResolution())[1];
    }

    /** 解析 "宽x高"；配置 predicate 已限制格式，异常仅作兜底。 */
    private static int[] parseResolution(String value) {
        try {
            String[] parts = value == null ? new String[0] : value.split("x");
            int w = Integer.parseInt(parts[0]);
            int h = Integer.parseInt(parts[1]);
            if (w > 0 && h > 0) {
                return new int[] {w, h};
            }
        } catch (RuntimeException ignored) {
            // fallback
        }
        return new int[] {1280, 1024};
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
            boolean encodeAsync,
            double nearPlane,
            double farPlane,
            int soundRange,
            int chunkRescanMs,
            boolean audioEnabled,
            int sampleRate,
            int bitDepth,
            int channels,
            int pollIntervalMs,
            int threadPriority,
            double ringCapacitySec,
            int netPort,
            boolean bindLocalOnly,
            boolean lanEnabled,
            boolean tokenEnabled,
            int maxConnections,
            int maxQps,
            int frameCacheTtlSec,
            String recordingOutputDir,
            String filterDirectory,
            String ffmpegPath,
            String recordingFormat,
            boolean syncMonitorEnabled,
            int warnThresholdMs,
            boolean persistChanges,
            boolean logResourceLifecycle,
            boolean logFrameEvents,
            boolean logFrameDrops,
            boolean logPipelineTiming,
            boolean logBufferWatermark,
            boolean logGlThreadViolation,
            boolean clockLogging) {
    }
}
