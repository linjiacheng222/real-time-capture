package com.captureapi.video.camera;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.mixin.GameRendererAccessor;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.core.thread.CaptureRenderHook;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.video.PboPairResource;
import com.captureapi.video.encode.JpegEncoder;
import com.captureapi.video.filter.FilterRegistry;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.frame.RawFrame;
import com.captureapi.video.overlay.GpuOverlayRenderer;
import com.captureapi.video.overlay.OverlayRegistry;
import com.captureapi.video.render.CameraStateSnapshot;
import com.captureapi.video.render.CameraSyncHook;
import com.captureapi.video.render.OffscreenPoseContext;
import com.captureapi.video.render.RenderStateSnapshot;
import com.captureapi.video.shader.ShaderCompatHooks;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * P2.3 多相机独立渲染（v2）：每个非 active 相机独立 FBO/PBO/JPEG 编码器，渲染线程内按时间片
 * 顺序重渲染，不共享主 active 管线的 FBO/PBO。
 *
 * <p>仍是时间片（约 250ms 轮询一次）而非同一帧并行；主 active 连续流由
 * OffscreenRenderPipeline 负责，本类只补非 active 相机。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MultiCameraRenderer implements CaptureRenderHook.AfterLevelListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final MultiCameraRenderer INSTANCE = new MultiCameraRenderer();
    private static final AtomicBoolean RENDERING = new AtomicBoolean(false);
    private static final AtomicLong RENDERED = new AtomicLong();

    private final Map<Integer, CameraTarget> targets = new HashMap<>();
    private long lastRenderNanos;
    private static volatile long lastFrameNanos;
    private int roundRobin;

    private MultiCameraRenderer() {
    }

    public static void install() {
        CaptureRenderHook.addAfterLevelListener(INSTANCE);
        LOGGER.info("{} [MULTICAM] P2.3 v2 独立 FBO/PBO 渲染器已安装（interval={}ms）",
                CaptureConstants.LOG_MARKER, CaptureConstants.MULTI_CAMERA_INTERVAL_MS);
    }

    @Override
    public void onAfterLevel(RenderLevelStageEvent event) {
        if (OffscreenPoseContext.current() != null || !WorldResourceManager.get().isWorldReady()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long now = CaptureClock.get().nowNanos();
        int targetFps = CaptureParams.get().multiCameraFps();
        if (targetFps <= 0) {
            return;
        }
        long intervalNanos = 1_000_000_000L / targetFps;
        if (now - lastRenderNanos < intervalNanos) {
            return;
        }
        lastRenderNanos = now;
        List<CameraSpec> cameras = CameraManager.get().list();
        if (cameras.size() <= 1) {
            cleanupStale(cameras);
            return;
        }
        int active = CameraManager.get().activeCameraId();
        List<CameraSpec> candidates = new ArrayList<>();
        for (CameraSpec spec : cameras) {
            if (spec.id() > 0 && spec.id() != active && !spec.paused()
                    && CameraFrameStore.hasConsumer(spec.id(), now)) {
                candidates.add(spec);
            }
        }
        if (candidates.isEmpty()) {
            cleanupStale(cameras);
            return;
        }
        long budgetNanos = Math.max(1L, CaptureParams.get().multiCameraBudgetMs())
                * CaptureConstants.NANOS_PER_MILLI;
        long deadline = now + budgetNanos;
        int rendered = 0;
        int index = Math.floorMod(roundRobin, candidates.size());
        while (CaptureClock.get().nowNanos() < deadline && rendered < candidates.size()) {
            CameraSpec spec = candidates.get(index % candidates.size());
            index++;
            rendered++;
            renderCamera(spec, event.getPartialTick());
        }
        roundRobin = index;
        cleanupStale(cameras);
    }

    /** @return 累计多路渲染帧数（V4 诊断） */
    public static long renderedFrames() {
        return RENDERED.get();
    }

    /** @return 最近一帧多路渲染时间戳（0=无） */
    public static long lastFrameNanos() {
        return lastFrameNanos;
    }

    private void cleanupStale(List<CameraSpec> cameras) {
        List<Integer> valid = new ArrayList<>();
        for (CameraSpec spec : cameras) {
            valid.add(spec.id());
        }
        for (Integer id : new ArrayList<>(targets.keySet())) {
            if (!valid.contains(id)) {
                destroyTarget(id);
                CameraFrameStore.remove(id);
            }
        }
    }

    private void renderCamera(CameraSpec spec, float partialTick) {
        if (!RENDERING.compareAndSet(false, true)) {
            return;
        }
        GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
        GameRendererAccessor accessor = (GameRendererAccessor) gameRenderer;
        boolean previousRenderHand = accessor.captureapi$isRenderHand();
        RenderStateSnapshot renderState = null;
        CameraStateSnapshot cameraState = null;
        CameraTarget target = null;
        try {
            target = targetFor(spec);
            if (target == null) {
                return;
            }
            CameraPose pose = CameraMatrices.capture(spec, partialTick);
            target.target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            target.target.clear(true);
            target.target.bindWrite(true);
            renderState = RenderStateSnapshot.capture();
            cameraState = CameraStateSnapshot.applyPose(gameRenderer.getMainCamera(), pose);
            accessor.captureapi$setRenderHand(false);
            ShaderCompatHooks.beginOffscreenPass(pose);
            CameraSyncHook.onBeforeOffscreenRender(pose);
            OffscreenPoseContext.push(pose);
            try {
                gameRenderer.renderLevel(partialTick, 0L, new PoseStack());
            } finally {
                OffscreenPoseContext.pop();
            }
            CameraSyncHook.onAfterOffscreenRender(pose);
            ShaderCompatHooks.endOffscreenPass();
            // renderLevel 可能重绑主 framebuffer；回读前必须重新绑定独立 FBO（S1/SpikeB 同类修复）
            target.target.bindWrite(true);
            GpuOverlayRenderer.render(target.width, target.height);
            RawFrame raw = target.pbo.readbackImmediate();
            target.target.unbindWrite();
            if (raw == null) {
                return;
            }
            RawFrame filtered = FilterRegistry.get().applySelected(raw);
            RawFrame composited = OverlayRegistry.get().apply(filtered);
            byte[] jpeg = target.encoder.encode(composited, CaptureParams.get().jpegQuality());
            JpegFrame published = new JpegFrame(jpeg, composited.width(),
                    composited.height(), composited.timestampNanos());
            CameraFrameStore.put(spec.id(), published);
            lastFrameNanos = published.timestampNanos();
            RENDERED.incrementAndGet();
        } catch (Throwable t) {
            LOGGER.warn("{} [MULTICAM] 相机 {} 独立渲染失败（已隔离）：{}",
                    CaptureConstants.LOG_MARKER, spec.id(), t.toString());
        } finally {
            try {
                if (target != null) {
                    target.target.unbindWrite();
                }
            } catch (Throwable ignored) {
                // 解绑异常忽略
            }
            if (cameraState != null) {
                cameraState.restore(gameRenderer.getMainCamera());
            }
            accessor.captureapi$setRenderHand(previousRenderHand);
            if (renderState != null) {
                renderState.restore();
            }
            RENDERING.set(false);
        }
    }

    private CameraTarget targetFor(CameraSpec spec) {
        CameraTarget current = targets.get(spec.id());
        if (current != null && current.width == spec.width() && current.height == spec.height()) {
            return current;
        }
        if (current != null) {
            current.destroy();
            targets.remove(spec.id());
        }
        CameraTarget created = CameraTarget.create(spec.width(), spec.height());
        targets.put(spec.id(), created);
        return created;
    }

    private void destroyTarget(int cameraId) {
        CameraTarget target = targets.remove(cameraId);
        if (target != null) {
            target.destroy();
        }
    }

    /** 单相机独立 GL 资源。 */
    private static final class CameraTarget {
        private final TextureTarget target;
        private final PboPairResource pbo;
        private final JpegEncoder encoder = new JpegEncoder();
        private final int width;
        private final int height;

        private CameraTarget(TextureTarget target, PboPairResource pbo, int width, int height) {
            this.target = target;
            this.pbo = pbo;
            this.width = width;
            this.height = height;
        }

        private static CameraTarget create(int width, int height) {
            GlThreadGuard.checkGlThread("MultiCameraRenderer.create");
            TextureTarget target = new TextureTarget(width, height, true, false);
            PboPairResource pbo = new PboPairResource(width, height);
            pbo.create();
            return new CameraTarget(target, pbo, width, height);
        }

        private void destroy() {
            pbo.destroy();
            target.destroyBuffers();
        }
    }
}
