package com.captureapi.core.thread;

import com.captureapi.CaptureInterface;
import com.captureapi.core.CaptureConstants;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渲染阶段事件骨架（I1.3.1）。
 *
 * <p>订阅 {@link RenderLevelStageEvent}，在 {@code Stage.AFTER_LEVEL} 时调用本类钩子；
 * 这是与游戏主渲染管线同生命周期的入口，M2 将在此接入"按时间间隔循环触发离屏渲染"。
 * M1 为空壳：仅帧计数/诊断日志，并向 M2 暴露监听器扩展点。</p>
 *
 * <p>线程规约：事件在渲染主线程回调；本类入口即调用 {@link GlThreadGuard#checkGlThread(String)}
 * 做运行期校验（红线 3）。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CaptureRenderHook {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    /** AFTER_LEVEL 回调扩展点（M2 的离屏渲染管线注册于此）。 */
    @FunctionalInterface
    public interface AfterLevelListener {
        void onAfterLevel(RenderLevelStageEvent event);
    }

    private static final List<AfterLevelListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicLong FRAME_COUNTER = new AtomicLong();
    private static final AtomicLong LOG_INTERVAL = new AtomicLong(120L);

    /** 配置项 log_frame_events（默认 false；T1.5 接入 CaptureParams）。 */
    private static volatile boolean frameEventLogging = false;

    private CaptureRenderHook() {
        throw new AssertionError("no instances");
    }

    /**
     * 注册 AFTER_LEVEL 监听器（M2 使用；线程安全）。
     *
     * @param listener 监听器
     */
    public static void addAfterLevelListener(AfterLevelListener listener) {
        LISTENERS.add(listener);
    }

    /**
     * 移除 AFTER_LEVEL 监听器。
     *
     * @param listener 监听器
     * @return 是否移除成功
     */
    public static boolean removeAfterLevelListener(AfterLevelListener listener) {
        return LISTENERS.remove(listener);
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        GlThreadGuard.checkGlThread("CaptureRenderHook.onAfterLevel");

        long frame = FRAME_COUNTER.incrementAndGet();
        if (frameEventLogging && frame % LOG_INTERVAL.get() == 0) {
            LOGGER.info("{} 渲染帧计数={}（AFTER_LEVEL，诊断 log_frame_events）", CaptureConstants.LOG_MARKER, frame);
        }

        for (AfterLevelListener listener : LISTENERS) {
            try {
                listener.onAfterLevel(event);
            } catch (Throwable t) {
                LOGGER.error("{} AFTER_LEVEL 监听器异常（已隔离，不影响渲染主流程）", CaptureConstants.LOG_MARKER, t);
            }
        }
    }

    /** @return 自客户端启动以来的 AFTER_LEVEL 回调次数（诊断） */
    public static long frameCounter() {
        return FRAME_COUNTER.get();
    }

    /** 接入配置项 log_frame_events（T1.5 调用；默认 false）。 */
    public static void setFrameEventLogging(boolean enabled) {
        frameEventLogging = enabled;
    }

    /** @return 当前是否开启帧事件日志 */
    public static boolean isFrameEventLogging() {
        return frameEventLogging;
    }
}
