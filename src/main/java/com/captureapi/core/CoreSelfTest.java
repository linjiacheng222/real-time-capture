package com.captureapi.core;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.ResourceSelfTest;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.core.thread.LatestValueChannel;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.time.CaptureClock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M1 核心设施自检入口（T1.2~T1.5，仅开发环境）。
 *
 * <p>由 {@code captureapi.debug.selftest=true} 系统属性启用（build.gradle 的 dev runs 已设置），
 * 在客户端 setup 完成后调度：</p>
 * <ol>
 *   <li>非渲染线程负例：独立线程调用 {@link GlThreadGuard#checkGlThread(String)}，应产生违规日志；</li>
 *   <li>渲染线程正例（经 {@code RenderSystem.recordRenderCall} 调度）：GL 守卫通过、
 *       最新值通道 1000 次发布/读取正确、时钟单调性自检、参数快照（配置）校验、
 *       {@link ResourceSelfTest} 资源管理器生命周期自检通过。</li>
 * </ol>
 * <p>生产环境不设置该属性，本类不产生任何行为。</p>
 */
public final class CoreSelfTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final AtomicBoolean SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean RENDER_THREAD_DONE = new AtomicBoolean(false);

    private CoreSelfTest() {
        throw new AssertionError("no instances");
    }

    /** 若启用开发自检属性则排期执行（幂等；由 ClientSetupHandler 调用）。 */
    public static void scheduleIfEnabled() {
        if (!Boolean.getBoolean(CaptureConstants.DEBUG_SELFTEST_PROPERTY)) {
            return;
        }
        if (!SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("{} 开发自检已排期（{} = true）",
                CaptureConstants.LOG_MARKER, CaptureConstants.DEBUG_SELFTEST_PROPERTY);

        Thread nonRender = new Thread(CoreSelfTest::runNonRenderThreadCheck, "captureapi-selftest-nonrender");
        nonRender.setDaemon(true);
        nonRender.start();

        try {
            GlThreadGuard.runOnRenderThread(CoreSelfTest::runRenderThreadCheck);
        } catch (Throwable t) {
            LOGGER.error("{} 核心自检调度失败（RenderSystem.recordRenderCall）", CaptureConstants.LOG_MARKER, t);
        }
    }

    /** @return 渲染线程自检是否已完成（诊断用） */
    public static boolean isRenderThreadCheckDone() {
        return RENDER_THREAD_DONE.get();
    }

    private static void runNonRenderThreadCheck() {
        try {
            boolean negative = !GlThreadGuard.isOnRenderThread();
            // 预期产生一条违规日志（自检负例）
            GlThreadGuard.checkGlThread("selftest-nonrender-expected-violation");
            LOGGER.info("{} 自检负例完成：非渲染线程判定={}（上方 GL 线程违规日志为预期）",
                    CaptureConstants.LOG_MARKER, negative);
        } catch (Throwable t) {
            LOGGER.error("{} 自检负例异常（预期不应抛出：断言模式未开启）", CaptureConstants.LOG_MARKER, t);
        }
    }

    private static void runRenderThreadCheck() {
        try {
            boolean onRenderThread = GlThreadGuard.isOnRenderThread();
            GlThreadGuard.checkGlThread("selftest-render");

            LatestValueChannel<Object> channel = new LatestValueChannel<>();
            for (int i = 0; i < 1000; i++) {
                channel.publish(new Object());
            }
            boolean channelOk = channel.latest() != null && channel.publishedCount() == 1000L;
            channel.clear();
            boolean clearOk = channel.latest() == null && channel.latestOr(null) == null;

            boolean clockOk = CaptureClock.get().selfTestMonotonic(1000);
            boolean configOk;
            try {
                CaptureParams params = CaptureParams.get();
                boolean loaded = params.awaitLoaded(5000L);
                String resolution = loaded ? params.videoResolution() : null;
                configOk = loaded
                        && params.fpsMax() >= 1 && params.fpsMax() <= 240
                        && params.netPort() >= 1024 && params.netPort() <= 65535
                        && params.sampleRate() >= 8000 && params.sampleRate() <= 192000
                        && (params.channels() == 1 || params.channels() == 2)
                        && resolution != null && resolution.matches("\\d{2,5}x\\d{2,5}");
                if (loaded) {
                    LOGGER.info("{} 配置快照校验：fps_max={}, video_resolution={}, net_port={}, sample_rate={}, channels={}, bit_depth={}",
                            CaptureConstants.LOG_MARKER, params.fpsMax(), resolution, params.netPort(),
                            params.sampleRate(), params.channels(), params.bitDepth());
                }
            } catch (Throwable t) {
                LOGGER.error("{} 配置快照自检异常", CaptureConstants.LOG_MARKER, t);
                configOk = false;
            }

            boolean resourceOk = ResourceSelfTest.run();

            boolean pass = onRenderThread && channelOk && clearOk && clockOk && configOk && resourceOk;
            RENDER_THREAD_DONE.set(true);
            if (pass) {
                LOGGER.info("{} 核心自检 PASS（renderThread={}，channel 1000 次发布/读取={}，clock={}，config={}，资源管理器={}）",
                        CaptureConstants.LOG_MARKER, onRenderThread, channelOk, clockOk, configOk, resourceOk);
            } else {
                LOGGER.error("{} 核心自检 FAIL（renderThread={}，channel={}，clear={}，clock={}，config={}，resource={}）",
                        CaptureConstants.LOG_MARKER, onRenderThread, channelOk, clearOk, clockOk, configOk, resourceOk);
            }
        } catch (Throwable t) {
            RENDER_THREAD_DONE.set(true);
            LOGGER.error("{} 核心自检异常", CaptureConstants.LOG_MARKER, t);
        }
    }
}
