package com.captureapi.core;

/**
 * 捕获接口（captureapi）全局常量白名单（红线 1 唯一例外通道）。
 *
 * <p>T1.1 阶段仅落位日志前缀等基础设施常量；完整白名单清单见规划
 * {@code M1/T1.5-参数注册框架骨架/I1.5.2-常量类与白名单机制} 与
 * {@code 附录B-全参数清单 §四}，在 T1.5 实现时一次性补齐并逐条登记理由。</p>
 *
 * <p>纪律：凡用户可能需要调整的数值一律走 TOML（{@code CaptureParams} 门面）；
 * 本类只允许"改动即破坏机制"的结构常数，且每条必须带理由。</p>
 */
public final class CaptureConstants {

    /** 日志统一前缀（诊断检索锚；与附录 B §四白名单一致）。 */
    public static final String LOG_MARKER = "[CaptureAPI]";

    /** 模组 ID（与 gradle.properties#modId、mods.toml 模板变量一致）。 */
    public static final String MOD_ID = "captureapi";

    /** 模组版本（与 gradle.properties#modVersion 一致，由构建元数据同步）。 */
    public static final String MOD_VERSION = "0.5.0";

    /** 资源键统一前缀（附录 B §四白名单条目；T1.2 资源注册使用）。 */
    public static final String RESOURCE_KEY_PREFIX = "captureapi:";

    /** 音频采集线程名（M3，T1.3.4 线程命名规范）。 */
    public static final String THREAD_AUDIO = "captureapi-audio";

    /** 网络 IO 线程名（M4）。 */
    public static final String THREAD_NET = "captureapi-net";

    /** 编码线程名（M2，encode_async=true 时启用）。 */
    public static final String THREAD_ENCODER = "captureapi-encoder";

    private CaptureConstants() {
        throw new AssertionError("no instances");
    }
}
