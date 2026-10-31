package com.captureapi.video.render;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.mixin.GameRendererAccessor;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.core.thread.CaptureRenderHook;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.video.FrameRateLimiter;
import com.captureapi.video.OffscreenFboResource;
import com.captureapi.video.PboPairResource;
import com.captureapi.video.encode.EncodingPipeline;
import com.captureapi.video.encode.JpegEncoder;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.filter.FilterRegistry;
import com.captureapi.video.overlay.OverlayRegistry;
import com.captureapi.video.frame.RawFrame;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraMatrices;
import com.captureapi.video.camera.CameraPose;
import com.captureapi.video.camera.CameraSpec;
import com.captureapi.video.chunk.CaptureChunkPlanner;
import com.captureapi.video.shader.ShaderCompatHooks;
import com.captureapi.video.stream.OneShotRequest;
import com.captureapi.video.stream.VideoStreamHub;
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
    private final JpegEncoder oneShotEncoder = new JpegEncoder();
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
        // 实时调取优先且与流式同帧互斥：有请求时本帧只渲染临时相机
        OneShotRequest oneShot = VideoStreamHub.get().pollPending();
        if (oneShot != null) {
            renderOneShot(oneShot, event.getPartialTick());
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

    /**
     * 实时调取：临时相机渲染一帧→立即回读→编码→完成 Future（I2.2.4）。
     *
     * <p>不受 fps 限流约束；不进 CameraManager；与流式同帧互斥（调用方已保证）。
     * 失败/世界未就绪/回读失败均以异常完成 Future，不阻塞主画面。</p>
     */
    private void renderOneShot(OneShotRequest request, float partialTick) {
        GlThreadGuard.checkGlThread("OffscreenRenderPipeline.renderOneShot");
        if (!rendering.compareAndSet(false, true)) {
            request.fail(new IllegalStateException("渲染线程重入，实时调取被拒绝"));
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !WorldResourceManager.get().isWorldReady()) {
            request.fail(new IllegalStateException("世界未就绪，实时调取失败"));
            rendering.set(false);
            return;
        }
        GameRenderer gameRenderer = minecraft.gameRenderer;
        GameRendererAccessor gameRendererAccessor = (GameRendererAccessor) gameRenderer;
        boolean previousRenderHand = gameRendererAccessor.captureapi$isRenderHand();
        RenderStateSnapshot renderState = null;
        CameraStateSnapshot cameraState = null;
        OffscreenFboResource fbo = null;
        try {
            CameraSpec temporary = CameraManager.buildTemporary(request.params());
            CameraPose pose = CameraMatrices.capture(temporary, partialTick);
            renderState = RenderStateSnapshot.capture();
            cameraState = CameraStateSnapshot.applyPose(gameRenderer.getMainCamera(), pose);
            gameRendererAccessor.captureapi$setRenderHand(false);

            fbo = WorldResourceManager.get().get(OffscreenFboResource.KEY, OffscreenFboResource.class);
            TextureTarget target = fbo.target();
            target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            target.clear(true);
            fbo.bind();
            ShaderCompatHooks.beginOffscreenPass(pose);
            CameraSyncHook.onBeforeOffscreenRender(pose);
            gameRenderer.renderLevel(partialTick, 0L, new PoseStack());
            CameraSyncHook.onAfterOffscreenRender(pose);
            ShaderCompatHooks.endOffscreenPass();
            PboPairResource pbo = WorldResourceManager.get().get(PboPairResource.KEY, PboPairResource.class);
            RawFrame raw = pbo.readbackImmediate();
            fbo.unbind();
            if (raw == null) {
                throw new IllegalStateException("实时调取 PBO 回读失败");
            }
            RawFrame filtered = FilterRegistry.get().applySelected(raw);
            RawFrame composited = OverlayRegistry.get().apply(filtered);
            byte[] jpeg = oneShotEncoder.encode(composited, CaptureParams.get().jpegQuality());
            JpegFrame frame = new JpegFrame(jpeg, composited.width(), composited.height(),
                    composited.timestampNanos());
            if (request.complete(frame)) {
                LOGGER.info("{} 实时调取完成：{}x{}，jpeg={} bytes，ts={}", CaptureConstants.LOG_MARKER,
                        frame.width(), frame.height(), frame.jpeg().length, frame.timestampNanos());
            }
        } catch (Throwable t) {
            request.fail(t);
            LOGGER.error("{} 实时调取失败（已隔离）：{}", CaptureConstants.LOG_MARKER, t.toString());
        } finally {
            try {
                if (fbo != null && fbo.isCreated()) {
                    fbo.unbind();
                }
            } catch (Throwable ignored) {
                // 忽略解绑异常
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
            CaptureChunkPlanner.get().update(pose);
            renderState = RenderStateSnapshot.capture();
            cameraState = CameraStateSnapshot.applyPose(gameRenderer.getMainCamera(), pose);
            gameRendererAccessor.captureapi$setRenderHand(false);

            TextureTarget target = fbo.target();
            target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            target.clear(true);
            fbo.bind();
            ShaderCompatHooks.beginOffscreenPass(pose);
            // TODO(M3)：CameraSyncHook.onBeforeOffscreenRender(pose)，同帧内改写监听器
            long renderStart = CaptureClock.get().nowNanos();
            gameRenderer.renderLevel(partialTick, 0L, new PoseStack());
            long renderNanos = CaptureClock.get().nowNanos() - renderStart;
            CameraSyncHook.onAfterOffscreenRender(pose);
            ShaderCompatHooks.endOffscreenPass();
            // T2.4：FBO 仍为读/写目标时回读上一帧（写 N 读 N-1）
            PboPairResource pbo = WorldResourceManager.get().get(PboPairResource.KEY, PboPairResource.class);
            long readbackStart = CaptureClock.get().nowNanos();
            RawFrame frame = pbo.readback();
            long readbackNanos = CaptureClock.get().nowNanos() - readbackStart;
            if (frame != null) {
                lastFrame = frame;
                readbackFrames.incrementAndGet();
                if (readbackFrames.get() == 1L) {
                    LOGGER.info("{} 离屏回读首帧：{}x{}，{} bytes，ts={}",
                            CaptureConstants.LOG_MARKER, frame.width(), frame.height(), frame.byteSize(),
                            frame.timestampNanos());
                }
                long encodeStart = CaptureClock.get().nowNanos();
                EncodingPipeline.get().accept(frame);
                long encodeNanos = CaptureClock.get().nowNanos() - encodeStart;
                PipelineTimings.record(renderNanos, readbackNanos, encodeNanos);
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
