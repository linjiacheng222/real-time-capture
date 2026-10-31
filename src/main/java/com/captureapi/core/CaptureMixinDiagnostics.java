package com.captureapi.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin 基建自检的诊断回调（I1.1.1）。
 *
 * <p>独立于 Mixin 类持有日志器，避免在 Mixin 类中声明静态字段（静态字段会被 Mixin
 * 合并进目标类，可能污染目标类自身的日志器等静态状态）。本类仅被
 * {@code com.captureapi.core.mixin.MinecraftMixin} 的自检注入调用，DEBUG 级输出，
 * 不改变游戏行为；首个真实业务 Mixin 落地后可连同自检 Mixin 一并评估移除。</p>
 */
public final class CaptureMixinDiagnostics {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private CaptureMixinDiagnostics() {
        throw new AssertionError("no instances");
    }

    /** Mixin 注入点在 {@code Minecraft#run} HEAD 触发时调用（仅 DEBUG/显式自检开关输出）。 */
    public static void onMinecraftRunHead() {
        if (Boolean.getBoolean("captureapi.debug.mixin")) {
            LOGGER.info("{} Mixin 基建自检：Minecraft#run 注入生效（refmap/AT 管线可用）",
                    CaptureConstants.LOG_MARKER);
        } else {
            LOGGER.debug("{} Mixin 基建自检：Minecraft#run 注入生效（refmap/AT 管线可用）",
                    CaptureConstants.LOG_MARKER);
        }
    }
}
