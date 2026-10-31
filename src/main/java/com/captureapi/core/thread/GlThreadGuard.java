package com.captureapi.core.thread;

import com.captureapi.core.constant.CaptureConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GL 主线程守卫（I1.3.2）。
 *
 * <p>红线 3 的运行期执行者：本模组所有 GL 相关公开方法入口第一行必须调用
 * {@link #checkGlThread(String)}；M2 的 FBO/PBO/纹理封装、M3 的监听器同步均强制执行。</p>
 *
 * <p>需要从非渲染线程投递 GL 操作时，统一走 {@link #runOnRenderThread(Runnable)}
 * （内部使用 {@code RenderSystem.recordRenderCall}，1.20.1 可用性已编译验证），
 * 禁止直接跨线程调用 GL。</p>
 *
 * <p>配置项 {@code log_gl_thread_violation}（附录 B，默认 true）在 T1.5 配置框架落地后
 * 通过 {@link #setViolationLogging(boolean)} 接入；当前默认开启违规日志。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class GlThreadGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    /** 违规日志开关（T1.5：由 CaptureParams 的 log_gl_thread_violation 驱动）。 */
    private static volatile boolean violationLogging = true;

    /** 开发期断言模式（系统属性 captureapi.debug.glAssert=true 时违规抛异常，便于早期暴露）。 */
    private static final boolean ASSERT_IN_VIOLATION = Boolean.getBoolean("captureapi.debug.glAssert");

    private GlThreadGuard() {
        throw new AssertionError("no instances");
    }

    /** @return 当前线程是否为渲染主线程（Mojmap：RenderSystem#isOnRenderThread） */
    public static boolean isOnRenderThread() {
        return RenderSystem.isOnRenderThread();
    }

    /**
     * 断言当前线程是渲染主线程；否则按配置记录违规日志（开发期断言模式抛异常）。
     *
     * @param operation 调用方操作名（用于定位，如 "FBO.create"）
     */
    public static void checkGlThread(String operation) {
        if (RenderSystem.isOnRenderThread()) {
            return;
        }
        String threadName = Thread.currentThread().getName();
        if (violationLogging) {
            LOGGER.error("{} GL 线程违规：操作={} 当前线程={}（GL 只允许在渲染主线程执行，红线 3）",
                    CaptureConstants.LOG_MARKER, operation, threadName);
        }
        if (ASSERT_IN_VIOLATION) {
            throw new IllegalStateException("GL 调用不在渲染主线程：operation=" + operation + ", thread=" + threadName);
        }
    }

    /**
     * 从任意线程投递一个任务到渲染主线程执行（跨线程 GL 操作的唯一合法入口）。
     *
     * @param task 要在渲染主线程执行的任务
     */
    public static void runOnRenderThread(Runnable task) {
        RenderSystem.recordRenderCall(task::run);
    }

    /** 接入配置项 log_gl_thread_violation（T1.5 调用；默认 true）。 */
    public static void setViolationLogging(boolean enabled) {
        violationLogging = enabled;
    }

    /** @return 当前是否开启违规日志（自检/诊断用） */
    public static boolean isViolationLogging() {
        return violationLogging;
    }
}
