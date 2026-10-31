package com.captureapi.core.config;

import com.captureapi.core.constant.CaptureConstants;
import java.util.List;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * ForgeConfigSpec 配置骨架（I1.5.1，红线 1 的机制基础）。
 *
 * <p>四 section：{@code [video]}、{@code [audio]}、{@code [server]}、{@code [log]}；M1 落位已冻结默认值
 * 参数（fps_max/video_resolution/jpeg_quality/audio_enabled/sample_rate/bit_depth/channels/
 * net_port/bind_local_only/lan_enabled/token_enabled）与 M1 诊断参数（log_* / clock_logging）。
 * 其余参数由 M6 按附录 B 归并追加。</p>
 *
 * <p>默认值与范围以《附录B-全参数清单》为准；运行时只能经 {@link CaptureParams} 门面读取，
 * 模块代码禁止直接持有 {@link ForgeConfigSpec.ConfigValue}。</p>
 */
public final class CaptureConfig {

    /** 客户端配置 spec（在模组构造器注册为 CLIENT 类型，文件名 captureapi-client.toml）。 */
    public static final ForgeConfigSpec SPEC;

    // ===== [video] =====
    static final ForgeConfigSpec.IntValue FPS_MAX;
    static final ForgeConfigSpec.ConfigValue<String> VIDEO_RESOLUTION;
    static final ForgeConfigSpec.IntValue JPEG_QUALITY;
    static final ForgeConfigSpec.BooleanValue ENCODE_ASYNC;
    // M2/T2.1.2：相机投影裁剪面
    static final ForgeConfigSpec.DoubleValue NEAR_PLANE;
    static final ForgeConfigSpec.DoubleValue FAR_PLANE;
    // M2/T2.7：摄制区块按需加载
    static final ForgeConfigSpec.IntValue SOUND_RANGE;
    static final ForgeConfigSpec.IntValue CHUNK_RESCAN_MS;

    // ===== [audio] =====
    static final ForgeConfigSpec.BooleanValue AUDIO_ENABLED;
    static final ForgeConfigSpec.IntValue SAMPLE_RATE;
    static final ForgeConfigSpec.ConfigValue<Integer> BIT_DEPTH;
    static final ForgeConfigSpec.IntValue CHANNELS;
    static final ForgeConfigSpec.IntValue POLL_INTERVAL_MS;
    static final ForgeConfigSpec.IntValue THREAD_PRIORITY;
    static final ForgeConfigSpec.DoubleValue RING_CAPACITY_SEC;

    // ===== [server] =====
    static final ForgeConfigSpec.IntValue NET_PORT;
    static final ForgeConfigSpec.BooleanValue BIND_LOCAL_ONLY;
    static final ForgeConfigSpec.BooleanValue LAN_ENABLED;
    static final ForgeConfigSpec.BooleanValue TOKEN_ENABLED;
    static final ForgeConfigSpec.IntValue MAX_CONNECTIONS;
    static final ForgeConfigSpec.IntValue MAX_QPS;
    static final ForgeConfigSpec.IntValue FRAME_CACHE_TTL_SEC;
    static final ForgeConfigSpec.ConfigValue<String> RECORDING_OUTPUT_DIR;
    static final ForgeConfigSpec.ConfigValue<String> FILTER_DIRECTORY;
    static final ForgeConfigSpec.ConfigValue<String> OVERLAY_DIRECTORY;
    static final ForgeConfigSpec.BooleanValue OVERLAY_ENABLED;
    static final ForgeConfigSpec.ConfigValue<String> FFMPEG_PATH;
    static final ForgeConfigSpec.ConfigValue<String> RECORDING_FORMAT;
    static final ForgeConfigSpec.BooleanValue SYNC_MONITOR_ENABLED;
    static final ForgeConfigSpec.BooleanValue PERSIST_CHANGES;
    static final ForgeConfigSpec.IntValue WARN_THRESHOLD_MS;

    // ===== [log] =====
    static final ForgeConfigSpec.BooleanValue LOG_RESOURCE_LIFECYCLE;
    static final ForgeConfigSpec.BooleanValue LOG_FRAME_EVENTS;
    static final ForgeConfigSpec.BooleanValue LOG_FRAME_DROPS;
    static final ForgeConfigSpec.BooleanValue LOG_PIPELINE_TIMING;
    static final ForgeConfigSpec.BooleanValue LOG_BUFFER_WATERMARK;
    static final ForgeConfigSpec.BooleanValue LOG_GL_THREAD_VIOLATION;
    static final ForgeConfigSpec.BooleanValue CLOCK_LOGGING;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("捕获接口（captureapi）客户端参数。默认值以《附录B-全参数清单》为准；改后需重启客户端。")
                .push("video");
        FPS_MAX = builder
                .comment("离屏渲染帧率上限（cap 非保证；红线 4）")
                .defineInRange("fps_max", 30, 1, 240);
        VIDEO_RESOLUTION = builder
                .comment("离屏渲染分辨率（宽x高，FBO 重建生效）")
                .define("video_resolution", "1280x1024", value -> value instanceof String s
                        && s.matches("\\d{2,5}x\\d{2,5}"));
        JPEG_QUALITY = builder
                .comment("JPEG 编码质量（0–100）")
                .defineInRange("jpeg_quality", 75, 0, 100);
        ENCODE_ASYNC = builder
                .comment("异步编码线程开关（默认 false=渲染线程内联；T2.5.2）")
                .define("encode_async", false);
        NEAR_PLANE = builder
                .comment("离屏投影近裁剪距离（建议 0.05，实测定稿）")
                .defineInRange("near_plane", 0.05D, 0.001D, 16.0D);
        FAR_PLANE = builder
                .comment("离屏投影远裁剪距离；0=自动（渲染距离×16）")
                .defineInRange("far_plane", 0.0D, 0.0D, 4096.0D);
        SOUND_RANGE = builder
                .comment("相机可听半径（格；声音区块求取，T2.7.1）")
                .defineInRange("sound_range", 64, 0, 512);
        CHUNK_RESCAN_MS = builder
                .comment("摄制区块集合重扫周期（毫秒，T2.7.1）")
                .defineInRange("chunk_rescan_ms", 500, 100, 5000);
        builder.pop();

        builder.comment("音频采集与格式参数（M3 使用）").push("audio");
        AUDIO_ENABLED = builder
                .comment("音频模块总开关（探测失败时亦自动禁用）")
                .define("audio_enabled", true);
        SAMPLE_RATE = builder
                .comment("采样率（Hz）")
                .defineInRange("sample_rate", 44100, 8000, 192000);
        BIT_DEPTH = builder
                .comment("采样位深（bit；当前仅支持 16）")
                .defineInList("bit_depth", 16, List.of(16));
        CHANNELS = builder
                .comment("声道数（1=单声道，2=立体声）")
                .defineInRange("channels", 2, 1, 2);
        POLL_INTERVAL_MS = builder
                .comment("环回采集轮询间隔（毫秒，T3.1.4）")
                .defineInRange("poll_interval_ms", 10, 2, 100);
        THREAD_PRIORITY = builder
                .comment("采集线程优先级（Java 1~10；默认 9=MAX-1，T3.1.3）")
                .defineInRange("thread_priority", 9, 1, 10);
        RING_CAPACITY_SEC = builder
                .comment("环形音频缓冲容量（秒，T3.2.3）")
                .defineInRange("ring_capacity_sec", 1.0D, 0.1D, 10.0D);
        builder.pop();

        builder.comment("对外网络服务参数（M4 使用）").push("server");
        NET_PORT = builder
                .comment("HTTP/WS 监听端口（默认 25580）")
                .defineInRange("net_port", 25580, 1024, 65535);
        BIND_LOCAL_ONLY = builder
                .comment("仅绑定本机 127.0.0.1（false 时配合 lan_enabled）")
                .define("bind_local_only", true);
        LAN_ENABLED = builder
                .comment("开放局域网（开启时显著警告）")
                .define("lan_enabled", false);
        TOKEN_ENABLED = builder
                .comment("token 鉴权开关（默认关）")
                .define("token_enabled", false);
        MAX_CONNECTIONS = builder
                .comment("WS 并发连接上限（T4.1.4；建议值）")
                .defineInRange("max_connections", 8, 1, 64);
        MAX_QPS = builder
                .comment("HTTP 单帧轮询限速（QPS，T4.5.3；建议值）")
                .defineInRange("max_qps", 30, 1, 240);
        FRAME_CACHE_TTL_SEC = builder
                .comment("临时相机帧缓存 TTL（秒，T4.9.3/W-084）")
                .defineInRange("frame_cache_ttl_sec", 30, 1, 600);
        RECORDING_OUTPUT_DIR = builder
                .comment("帧/录像默认输出目录（相对游戏目录）")
                .define("recording_output_dir", "config/captureapi/recordings");
        FILTER_DIRECTORY = builder
                .comment("滤镜目录（相对游戏目录，T8.1）")
                .define("filter_directory", "config/captureapi/filters");
        OVERLAY_DIRECTORY = builder
                .comment("叠加贴图目录（相对游戏目录，T8.2）")
                .define("overlay_directory", "config/captureapi/overlays");
        OVERLAY_ENABLED = builder
                .comment("叠加功能总开关（T8.2，默认 false）")
                .define("overlay_enabled", false);
        FFMPEG_PATH = builder
                .comment("FFmpeg 可执行路径（默认 PATH 中的 ffmpeg）")
                .define("ffmpeg_path", "ffmpeg");
        RECORDING_FORMAT = builder
                .comment("录像容器格式（默认 mp4；T4.10）")
                .defineInList("recording_format", "mp4", java.util.List.of("mp4"));
        SYNC_MONITOR_ENABLED = builder
                .comment("音画同步偏差监测开关（T5.3，默认 false=零开销）")
                .define("sync_monitor_enabled", false);
        WARN_THRESHOLD_MS = builder
                .comment("音画同步告警阈值（毫秒，T5.3，建议值）")
                .defineInRange("warn_threshold_ms", 50, 5, 500);
        PERSIST_CHANGES = builder
                .comment("运行时改参是否写回 TOML（M6/T6.3.2，默认 false）")
                .define("persist_changes", false);
        builder.pop();

        builder.comment("诊断日志开关（默认关闭；排障时按需开启）").push("log");
        LOG_RESOURCE_LIFECYCLE = builder
                .comment("资源创建/销毁轨迹日志（T1.2）")
                .define("log_resource_lifecycle", false);
        LOG_FRAME_EVENTS = builder
                .comment("渲染阶段帧事件日志（T1.3）")
                .define("log_frame_events", false);
        LOG_FRAME_DROPS = builder
                .comment("离屏渲染丢帧统计日志（T2.2.1，默认 false）")
                .define("log_frame_drops", false);
        LOG_PIPELINE_TIMING = builder
                .comment("流水线分段耗时统计日志（T2.5.3，默认 false）")
                .define("log_pipeline_timing", false);
        LOG_BUFFER_WATERMARK = builder
                .comment("音频环形缓冲水位日志（T3.2.3，默认 false）")
                .define("log_buffer_watermark", false);
        LOG_GL_THREAD_VIOLATION = builder
                .comment("GL 线程违规日志（T1.3，默认开启）")
                .define("log_gl_thread_violation", true);
        CLOCK_LOGGING = builder
                .comment("时钟自测/打标抽样日志（T1.4）")
                .define("clock_logging", false);
        builder.pop();

        SPEC = builder.build();
    }

    private CaptureConfig() {
        throw new AssertionError("no instances");
    }

    /** @return 配置文件名（与注册时一致） */
    public static String fileName() {
        return CaptureConstants.TOML_FILE_NAME;
    }
}
