package com.captureapi.core.constant;

/**
 * 捕获接口（captureapi）全局常量白名单（红线 1 唯一例外通道，T1.5.2）。
 *
 * <p><b>纪律</b>：凡用户可能需要调整的数值一律走 TOML（{@link com.captureapi.core.config.CaptureParams} 门面）；
 * 本类只允许"改动即破坏机制"的结构常数，且每条必须带理由。白名单与规划《附录B-全参数清单》§四
 * 同步登记，新增条目须经 T6.2.4 审查。</p>
 *
 * <p>理由分类：<b>协议</b>（改动即破坏对接兼容）、<b>结构</b>（成对/容量语义，改动破坏机制）、
 * <b>标识</b>（前缀/键/文件名，改动破坏寻址）、<b>换算</b>（数学常数）。</p>
 */
public final class CaptureConstants {

    // ===== 标识类 =====

    /** 日志统一前缀（诊断检索锚；附录 B §四白名单）。 */
    public static final String LOG_MARKER = "[CaptureAPI]";

    /** 模组 ID（与 gradle.properties#modId、mods.toml 模板变量一致）。 */
    public static final String MOD_ID = "captureapi";

    /** 模组版本（与 gradle.properties#modVersion 一致）。 */
    public static final String MOD_VERSION = "0.5.0";

    /** 资源键统一前缀（资源注册键寻址约定；附录 B §四白名单）。 */
    public static final String RESOURCE_KEY_PREFIX = "captureapi:";

    /** 客户端配置文件名（运行时载体固定名，与 T6.2.3 核验路径一致；附录 B §四白名单）。 */
    public static final String TOML_FILE_NAME = "captureapi-client.toml";

    /** 音频采集线程名（M3；T1.3.4 线程命名规范）。 */
    public static final String THREAD_AUDIO = "captureapi-audio";

    /** 网络 IO 线程名（M4）。 */
    public static final String THREAD_NET = "captureapi-net";

    /** 编码线程名（M2，encode_async=true 时启用）。 */
    public static final String THREAD_ENCODER = "captureapi-encoder";

    // ===== 结构类 =====

    /** 双 PBO 成对语义（写 N 读 N-1 交替；改值即破坏 PBO 对结构；附录 B §四白名单）。 */
    public static final int PBO_PAIR_COUNT = 2;

    /** RGBA8 单像素字节数（PBO 容量 = w×h×4 的派生系数；附录 B §四白名单）。 */
    public static final int RGBA_BYTES_PER_PIXEL = 4;

    /** 异步编码有界队列容量（满丢旧；队列语义结构常数；附录 B §四白名单）。 */
    public static final int ENCODE_QUEUE_CAPACITY = 2;

    /** 实时调取同帧请求上限（超出拒绝；队列结构常数；附录 B §四白名单）。 */
    public static final int ONESHOT_QUEUE_CAPACITY = 4;
    /** P2.3 v1 多相机时间片间隔（毫秒）。 */
    public static final long MULTI_CAMERA_INTERVAL_MS = 250L;
    /** P2.3 v1 多相机单次调取超时（毫秒）。 */
    public static final long MULTI_CAMERA_TIMEOUT_MS = 2000L;

    // ===== 换算类 =====

    /** 每秒纳秒数（帧率限流换算 1e9 / fps_max；附录 B §四白名单）。 */
    public static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** 每毫秒纳秒数（仅日志换算用；T1.4.1 toMillisForLog）。 */
    public static final long NANOS_PER_MILLI = 1_000_000L;

    // ===== 协议类（改动即破坏对接，契约冻结候选）=====

    /** 视频帧头固定长度（时间戳 long 8B + 宽/高 short×2 4B + 载荷长度 int 4B＝16B；附录 B §四白名单）。 */
    public static final int VIDEO_FRAME_HEADER_BYTES = 16;

    /** 音频块头固定长度（起止时间戳 8B×2 + 采样数 4B＝20B；协议常量）。 */
    public static final int AUDIO_CHUNK_HEADER_BYTES = 20;

    /** MJPEG multipart 边界串（T4.4 冻结前占位；改动须先改契约文档）。 */
    public static final String MJPEG_BOUNDARY = "captureapi-frame-boundary";

    // ===== 调试/自检系统属性（非参数；仅 dev 运行设置）=====

    /** 多 listener Phase 0 spike 开关（dev JVM 属性）。 */
    public static final String DEBUG_MULTILISTENER_SPIKE_PROPERTY = "captureapi.debug.multilistener_spike";
    /** 多 listener Phase 0 Channel/SoundBuffer 探针开关（dev JVM 属性）。 */
    public static final String DEBUG_MULTILISTENER_PROBE_PROPERTY = "captureapi.debug.multilistener_probe";
    /** Phase 0 合成源采样率（仅 spike）。 */
    public static final int MULTILISTENER_SPIKE_SAMPLE_RATE = 44100;
    /** Phase 0 合成源声道数（仅 spike）。 */
    public static final int MULTILISTENER_SPIKE_CHANNELS = 2;
    /** Phase 0 合成源采样数（10ms@44.1kHz）。 */
    public static final int MULTILISTENER_SPIKE_SAMPLES = 441;
    /** Phase 0 合成源频率（Hz）。 */
    public static final float MULTILISTENER_SPIKE_FREQUENCY = 441.0F;
    /** Phase 0 合成源/第二 listener 的 X 偏移（米）。 */
    public static final float MULTILISTENER_SPIKE_SOURCE_X = 8.0F;

    /** dev Mixin 自检日志开关（build.gradle dev runs 设置）。 */
    public static final String DEBUG_MIXIN_PROPERTY = "captureapi.debug.mixin";

    /** dev M1 核心自检开关（build.gradle dev runs 设置）。 */
    public static final String DEBUG_SELFTEST_PROPERTY = "captureapi.debug.selftest";

    /** GL 线程守卫开发期断言开关（默认关闭；排障时 -D 打开）。 */
    public static final String DEBUG_GL_ASSERT_PROPERTY = "captureapi.debug.glAssert";

    private CaptureConstants() {
        throw new AssertionError("no instances");
    }
}
