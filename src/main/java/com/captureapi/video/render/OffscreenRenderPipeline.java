package com.captureapi.video.render;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.mixin.GameRendererAccessor;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.core.thread.CaptureRenderHook;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.video.FrameRateLimiter;
import com.captureapi.video.OffscreenFboResource;
import com.captureapi.video.PboPairResource;
import com.captureapi.video.frame.RawFrame;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraMatrices;
import com.captureapi.video.camera.CameraPose;
import com.captureapi.video.camera.CameraSpec;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离屏场景重渲染流程（I2.2.3）。
 *
 * <p>AFTER_LEVEL 内按限流器放行：读 active 相机 → 保存渲染状态/相机状态 → 临时替换主相机
 * → 绑定离屏 FBO → 调 {@code GameRenderer#renderLevel} 重渲染完整场景 → 解绑 → 恢复相机/状态。
 * M3 的 CameraSyncHook 时序槽位以 TODO 标出，待 M3 接入。</p>
 *
 * <p>重入守卫：离屏 {@code renderLevel} 会再次触发 AFTER_LEVEL；{@link #rendering} 为 true 时
 * 所有本管线回调直接跳过（防递归）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class OffscreenRenderPipeline implements CaptureRenderHook.AfterLevelListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final OffscreenRenderPipeline INSTANCE = new OffscreenRenderPipeline();

    private final FrameRateLimiter limiter = new FrameRateLimiter();
    private final AtomicBoolean rendering = new AtomicBoolean(false);
    private final AtomicLong renderedFrames = new AtomicLong();
    private final AtomicLong failedFrames = new AtomicLong();
    private final AtomicLong readbackFrames = new AtomicLong();
    private volatile RawFrame lastFrame;
    private volatile boolean firstFrameLogged;

    private OffscreenRenderPipeline() {
    }

    /** 注册到 M1 的 AFTER_LEVEL 钩子（VideoSetupHandler 调用，幂等）。 */
    public static void install() {
        CaptureRenderHook.addAfterLevelListener(INSTANCE);
    }

    /** @return 已成功完成的离屏渲染帧数（诊断/验收） */
    public static long renderedFrames() {
        return INSTANCE.renderedFrames.get();
    }

    /** @return 失败跳过的帧数（诊断/验收） */
    public static long failedFrames() {
        return INSTANCE.failedFrames.get();
    }

    /** @return 最近一次回读成功的原始帧（无则 null；T2.5 编码消费） */
    public static RawFrame latestFrame() {
        return INSTANCE.lastFrame;
    }

    /** @return 回读成功帧数 */
    public static long readbackFrames() {
        return INSTANCE.readbackFrames.get();
    }

    @Override
    public void onAfterLevel(RenderLevelStageEvent event) {
        if (rendering.get()) {
            return;
        }
        CameraSpec spec = CameraManager.get().active();
        if (spec == null || spec.paused()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !WorldResourceManager.get().isWorldReady()) {
            return;
        }
        if (!limiter.allow(CaptureClock.get().nowNanos())) {
            return;
        }
        renderOffscreen(minecraft, spec, event.getPartialTick());
    }

    private void renderOffscreen(Minecraft minecraft, CameraSpec spec, float partialTick) {
        GlThreadGuard.checkGlThread("OffscreenRenderPipeline.render");
        if (!rendering.compareAndSet(false, true)) {
            return;
        }
        GameRenderer gameRenderer = minecraft.gameRenderer;
        GameRendererAccessor gameRendererAccessor = (GameRendererAccessor) gameRenderer;
        boolean previousRenderHand = gameRendererAccessor.captureapi$isRenderHand();
        RenderStateSnapshot renderState = null;
        CameraStateSnapshot cameraState = null;
        OffscreenFboResource fbo = null;
        try {
            fbo = WorldResourceManager.get().get(OffscreenFboResource.KEY, OffscreenFboResource.class);
            CameraPose pose = CameraMatrices.capture(spec, partialTick);
            renderState = RenderStateSnapshot.capture();
            cameraState = CameraStateSnapshot.applyPose(gameRenderer.getMainCamera(), pose);
            gameRendererAccessor.captureapi$setRenderHand(false);

            TextureTarget target = fbo.target();
            target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            target.clear(true);
            fbo.bind();
            // TODO(M3)：CameraSyncHook.onBeforeOffscreenRender(pose)，同帧内改写监听器
            gameRenderer.renderLevel(partialTick, 0L, new PoseStack());
            // TODO(M3)：CameraSyncHook.onAfterOffscreenRender(pose)，同帧内恢复监听器
            // T2.4：FBO 仍为读/写目标时回读上一帧（写 N 读 N-1）
            PboPairResource pbo = WorldResourceManager.get().get(PboPairResource.KEY, PboPairResource.class);
            RawFrame frame = pbo.readback();
            if (frame != null) {
                lastFrame = frame;
                readbackFrames.incrementAndGet();
                if (readbackFrames.get() == 1L) {
                    LOGGER.info("{} 离屏回读首帧：{}x{}，{} bytes，ts={}",
                            CaptureConstants.LOG_MARKER, frame.width(), frame.height(), frame.byteSize(),
                            frame.timestampNanos());
                }
            }
            fbo.unbind();

            renderedFrames.incrementAndGet();
            if (!firstFrameLogged) {
                firstFrameLogged = true;
                LOGGER.info("{} 离屏渲染首帧完成：相机 {}，{}x{}，累计帧={}",
                        CaptureConstants.LOG_MARKER, spec.id(), target.width, target.height,
                        renderedFrames.get());
            }
        } catch (Throwable t) {
            failedFrames.incrementAndGet();
            LOGGER.error("{} 离屏渲染失败（已隔离，主画面继续）：camera={}", CaptureConstants.LOG_MARKER, spec.id(), t);
        } finally {
            try {
                if (fbo != null && fbo.isCreated()) {
                    fbo.unbind();
                }
            } catch (Throwable ignored) {
                // 解绑失败忽略，后续状态恢复兜底
            }
            if (cameraState != null) {
                cameraState.restore(gameRenderer.getMainCamera());
            }
            gameRendererAccessor.captureapi$setRenderHand(previousRenderHand);
            if (renderState != null) {
                renderState.restore();
            }
            rendering.set(false);
        }
    }
}
