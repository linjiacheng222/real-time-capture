package com.captureapi.video.shader;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.video.camera.CameraPose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 光影环境探测与适配层 SPI（I2.6.1）。
 *
 * <p>探测 Oculus（Forge 1.20.1 的 Iris 移植）与 Embeddium；无光影环境走 no-op 分支，
 * 保证常态零额外开销。光影环境的具体离屏适配（I2.6.2，W-043）待查证后填充本 SPI 实现，
 * 本层只提供稳定挂点，不硬编码 Oculus 内部类。</p>
 *
 * <p>探测结果在客户端 setup 日志打印；无光影时 {@link #isActive()} 为 false，
 * 渲染管线不调用 begin/end（零分支）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ShaderCompatHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private static volatile boolean detected;
    private static volatile boolean active;
    private static volatile String backend = "none";

    private ShaderCompatHooks() {
        throw new AssertionError("no instances");
    }

    /** 客户端 setup 调用：探测光影/渲染优化模组并打印结果。 */
    public static void detect() {
        boolean oculus = isLoaded("oculus");
        boolean embeddium = isLoaded("embeddium");
        detected = oculus;
        active = oculus;
        backend = oculus ? "oculus" : "none";
        LOGGER.info("{} 光影环境探测：oculus={}，embeddium={}，离屏光影适配={}（W-043 待实装）",
                CaptureConstants.LOG_MARKER, oculus, embeddium, oculus ? "待实现" : "不适用");
    }

    /** @return 是否探测到光影环境（Oculus） */
    public static boolean detected() {
        return detected;
    }

    /** @return 是否需要调用光影适配钩子（无光影时 false，确保零开销） */
    public static boolean isActive() {
        return active;
    }

    /** @return 当前后端标识（日志/诊断） */
    public static String backend() {
        return backend;
    }

    /**
     * 离屏 pass 开始（I2.6.2 实装点；无光影不调用）。
     *
     * @param pose 当前帧相机位姿
     */
    public static void beginOffscreenPass(CameraPose pose) {
        if (!active) {
            return;
        }
        // TODO(W-043)：Oculus 离屏 shader/帧缓冲映射适配；当前仅记录，降级为无光影离屏帧。
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} 光影离屏 pass 适配入口（待实装）：camera={}", CaptureConstants.LOG_MARKER, pose.spec().id());
        }
    }

    /** 离屏 pass 结束（I2.6.2 实装点；无光影不调用）。 */
    public static void endOffscreenPass() {
        if (!active) {
            return;
        }
        // TODO(W-043)：恢复 Oculus 离屏 pass 状态。
    }

    private static boolean isLoaded(String modId) {
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
