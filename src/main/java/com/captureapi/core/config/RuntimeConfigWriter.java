package com.captureapi.core.config;

import com.captureapi.core.constant.CaptureConstants;
import net.minecraftforge.common.ForgeConfigSpec;
import com.google.gson.JsonElement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时改参写入器（M6/T6.3.1~3）。
 *
 * <p>唯一运行时写入口（WS config.set 调用）：即时类参数写 ForgeConfigSpec 值 → 重建
 * {@link CaptureParams} 快照（毫秒级生效）；需重启类参数拒绝并返回明确提示（不静默）；
 * {@code persist_changes=true} 时尝试 {@code SPEC.save()} 写回 TOML（成为新基线）。
 * 范围校验单点在此（与 spec Range 重复但更严者生效）。</p>
 */
public final class RuntimeConfigWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    /**
     * 写入结果。
     *
     * @param applied   是否已应用
     * @param key       参数名
     * @param value     实际应用值（未应用为 null）
     * @param persisted 是否已写回 TOML
     * @param message   说明（需重启/非法值原因）
     */
    public record Result(boolean applied, String key, Object value, boolean persisted, String message) {
    }

    private RuntimeConfigWriter() {
        throw new AssertionError("no instances");
    }

    /** @return 支持运行时读取的参数名→当前值（快照值） */
    public static Map<String, Object> getAll() {
        CaptureParams p = CaptureParams.get();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("fps_max", p.fpsMax());
        values.put("video_resolution", p.videoResolution());
        values.put("jpeg_quality", p.jpegQuality());
        values.put("encode_async", p.encodeAsync());
        values.put("near_plane", p.nearPlane());
        values.put("far_plane", p.farPlane());
        values.put("sound_range", p.soundRange());
        values.put("chunk_rescan_ms", p.chunkRescanMs());
        values.put("audio_enabled", p.audioEnabled());
        values.put("sample_rate", p.sampleRate());
        values.put("bit_depth", p.bitDepth());
        values.put("channels", p.channels());
        values.put("poll_interval_ms", p.pollIntervalMs());
        values.put("thread_priority", p.threadPriority());
        values.put("ring_capacity_sec", p.ringCapacitySec());
        values.put("net_port", p.netPort());
        values.put("bind_local_only", p.bindLocalOnly());
        values.put("lan_enabled", p.lanEnabled());
        values.put("token_enabled", p.tokenEnabled());
        values.put("max_connections", p.maxConnections());
        values.put("max_qps", p.maxQps());
        values.put("frame_cache_ttl_sec", p.frameCacheTtlSec());
        values.put("recording_output_dir", p.recordingOutputDir());
        values.put("ffmpeg_path", p.ffmpegPath());
        values.put("recording_format", p.recordingFormat());
        values.put("sync_monitor_enabled", p.syncMonitorEnabled());
        values.put("warn_threshold_ms", p.warnThresholdMs());
        values.put("persist_changes", p.persistChanges());
        values.put("log_resource_lifecycle", p.logResourceLifecycle());
        values.put("log_frame_events", p.logFrameEvents());
        values.put("log_frame_drops", p.logFrameDrops());
        values.put("log_pipeline_timing", p.logPipelineTiming());
        values.put("log_buffer_watermark", p.logBufferWatermark());
        values.put("log_gl_thread_violation", p.logGlThreadViolation());
        values.put("clock_logging", p.clockLogging());
        return values;
    }

    /** @return 当前值；未知参数空 */
    public static Optional<Object> get(String key) {
        return Optional.ofNullable(getAll().get(key));
    }

    /**
     * 写入一个参数。
     *
     * @param key   参数名
     * @param value 新值（JSON）
     * @return 写入结果
     */
    public static synchronized Result set(String key, JsonElement value) {
        if (key == null || key.isBlank()) {
            return new Result(false, key, null, false, "参数名不能为空");
        }
        if (value == null || value.isJsonNull()) {
            return new Result(false, key, null, false, "参数值不能为空");
        }
        try {
            switch (key) {
                case "fps_max" -> {
                    return intValue(key, value, 1, 240, CaptureConfig.FPS_MAX);
                }
                case "jpeg_quality" -> {
                    return intValue(key, value, 0, 100, CaptureConfig.JPEG_QUALITY);
                }
                case "encode_async" -> {
                    return boolValue(key, value, CaptureConfig.ENCODE_ASYNC);
                }
                case "near_plane" -> {
                    return doubleValue(key, value, 0.001D, 16.0D, CaptureConfig.NEAR_PLANE);
                }
                case "far_plane" -> {
                    return doubleValue(key, value, 0.0D, 4096.0D, CaptureConfig.FAR_PLANE);
                }
                case "sound_range" -> {
                    return intValue(key, value, 0, 512, CaptureConfig.SOUND_RANGE);
                }
                case "chunk_rescan_ms" -> {
                    return intValue(key, value, 100, 5000, CaptureConfig.CHUNK_RESCAN_MS);
                }
                case "audio_enabled" -> {
                    return boolValue(key, value, CaptureConfig.AUDIO_ENABLED);
                }
                case "poll_interval_ms" -> {
                    return intValue(key, value, 2, 100, CaptureConfig.POLL_INTERVAL_MS);
                }
                case "max_connections" -> {
                    return intValue(key, value, 1, 64, CaptureConfig.MAX_CONNECTIONS);
                }
                case "max_qps" -> {
                    return intValue(key, value, 1, 240, CaptureConfig.MAX_QPS);
                }
                case "frame_cache_ttl_sec" -> {
                    return intValue(key, value, 1, 600, CaptureConfig.FRAME_CACHE_TTL_SEC);
                }
                case "sync_monitor_enabled" -> {
                    return boolValue(key, value, CaptureConfig.SYNC_MONITOR_ENABLED);
                }
                case "warn_threshold_ms" -> {
                    return intValue(key, value, 5, 500, CaptureConfig.WARN_THRESHOLD_MS);
                }
                case "persist_changes" -> {
                    return boolValue(key, value, CaptureConfig.PERSIST_CHANGES);
                }
                case "recording_output_dir" -> {
                    return stringValue(key, value, CaptureConfig.RECORDING_OUTPUT_DIR);
                }
                case "ffmpeg_path" -> {
                    return stringValue(key, value, CaptureConfig.FFMPEG_PATH);
                }
                case "log_resource_lifecycle" -> {
                    return boolValue(key, value, CaptureConfig.LOG_RESOURCE_LIFECYCLE);
                }
                case "log_frame_events" -> {
                    return boolValue(key, value, CaptureConfig.LOG_FRAME_EVENTS);
                }
                case "log_frame_drops" -> {
                    return boolValue(key, value, CaptureConfig.LOG_FRAME_DROPS);
                }
                case "log_pipeline_timing" -> {
                    return boolValue(key, value, CaptureConfig.LOG_PIPELINE_TIMING);
                }
                case "log_buffer_watermark" -> {
                    return boolValue(key, value, CaptureConfig.LOG_BUFFER_WATERMARK);
                }
                case "log_gl_thread_violation" -> {
                    return boolValue(key, value, CaptureConfig.LOG_GL_THREAD_VIOLATION);
                }
                case "clock_logging" -> {
                    return boolValue(key, value, CaptureConfig.CLOCK_LOGGING);
                }
                // 需重启类：明确拒绝，不静默生效
                case "video_resolution", "sample_rate", "bit_depth", "channels", "thread_priority",
                        "ring_capacity_sec", "net_port", "bind_local_only", "token_enabled", "recording_format" ->
                        {
                            return new Result(false, key, null, false,
                                    "该参数需重启对应服务/采集/渲染后生效（运行时拒绝，避免结构不一致）");
                        }
                default -> {
                    return new Result(false, key, null, false, "未知参数：" + key);
                }
            }
        } catch (Throwable t) {
            return new Result(false, key, null, false, "参数应用失败：" + t.getMessage());
        }
    }

    private static Result intValue(String key, JsonElement value, int min, int max,
            ForgeConfigSpec.IntValue specValue) {
        int v;
        try {
            v = value.getAsInt();
        } catch (RuntimeException e) {
            return new Result(false, key, null, false, key + " 必须为整数");
        }
        if (v < min || v > max) {
            return new Result(false, key, null, false, key + " 范围 [" + min + "," + max + "]，实际 " + v);
        }
        specValue.set(v);
        CaptureParams.get().reloadForRuntimeChange();
        boolean persisted = persistIfNeeded();
        LOGGER.info("{} [CFG] 运行时改参：{}={}（persisted={}）", CaptureConstants.LOG_MARKER, key, v, persisted);
        return new Result(true, key, v, persisted, "已生效");
    }

    private static Result doubleValue(String key, JsonElement value, double min, double max,
            ForgeConfigSpec.DoubleValue specValue) {
        double v;
        try {
            v = value.getAsDouble();
        } catch (RuntimeException e) {
            return new Result(false, key, null, false, key + " 必须为数值");
        }
        if (!Double.isFinite(v) || v < min || v > max) {
            return new Result(false, key, null, false, key + " 范围 [" + min + "," + max + "]，实际 " + v);
        }
        specValue.set(v);
        CaptureParams.get().reloadForRuntimeChange();
        boolean persisted = persistIfNeeded();
        LOGGER.info("{} [CFG] 运行时改参：{}={}（persisted={}）", CaptureConstants.LOG_MARKER, key, v, persisted);
        return new Result(true, key, v, persisted, "已生效");
    }

    private static Result boolValue(String key, JsonElement value, ForgeConfigSpec.BooleanValue specValue) {
        boolean v;
        try {
            v = value.getAsBoolean();
        } catch (RuntimeException e) {
            return new Result(false, key, null, false, key + " 必须为布尔值");
        }
        specValue.set(v);
        CaptureParams.get().reloadForRuntimeChange();
        boolean persisted = persistIfNeeded();
        LOGGER.info("{} [CFG] 运行时改参：{}={}（persisted={}）", CaptureConstants.LOG_MARKER, key, v, persisted);
        return new Result(true, key, v, persisted, "已生效");
    }

    private static Result stringValue(String key, JsonElement value, ForgeConfigSpec.ConfigValue<String> specValue) {
        String v;
        try {
            v = value.getAsString();
        } catch (RuntimeException e) {
            return new Result(false, key, null, false, key + " 必须为字符串");
        }
        if (v.isBlank()) {
            return new Result(false, key, null, false, key + " 不能为空");
        }
        specValue.set(v);
        CaptureParams.get().reloadForRuntimeChange();
        boolean persisted = persistIfNeeded();
        LOGGER.info("{} [CFG] 运行时改参：{}={}（persisted={}）", CaptureConstants.LOG_MARKER, key, v, persisted);
        return new Result(true, key, v, persisted, "已生效");
    }

    private static boolean persistIfNeeded() {
        if (!CaptureParams.get().persistChanges()) {
            return false;
        }
        try {
            CaptureConfig.SPEC.save();
            return true;
        } catch (Throwable t) {
            LOGGER.warn("{} [CFG] 持久化写回 TOML 失败（运行态已生效）", CaptureConstants.LOG_MARKER, t);
            return false;
        }
    }
}
