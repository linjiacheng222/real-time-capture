package com.captureapi.core.mixin;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.camera.CameraPose;
import com.captureapi.video.render.CameraStateSnapshot;
import com.captureapi.video.render.OffscreenPoseContext;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 离屏渲染机位覆盖（S1 修复 / I2.3.2）。
 *
 * <p>原版 {@code GameRenderer#renderLevel} 在渲染前调用 {@code Camera#setup(...)}，会用玩家实体
 * 位置/角度覆盖我们预先写入的自定义机位。此 TAIL 注入在 setup 完成后重新应用离屏机位；
 * 仅在 {@link OffscreenPoseContext} 有值时生效（主画面零影响）。</p>
 */
@Mixin(Camera.class)
public abstract class CameraPoseMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final java.util.concurrent.atomic.AtomicLong APPLIED =
            new java.util.concurrent.atomic.AtomicLong();
    /** 诊断日志抽样间隔（帧）。 */
    private static final int DEBUG_LOG_INTERVAL = 600;

    @Inject(method = "setup", at = @At("TAIL"))
    private void captureapi$applyOffscreenPose(BlockGetter level, Entity entity, boolean detached,
            boolean mirror, float partialTick, CallbackInfo callbackInfo) {
        CameraPose pose = OffscreenPoseContext.current();
        if (pose != null) {
            CameraStateSnapshot.applyPoseOnly((Camera) (Object) this, pose);
            long count = APPLIED.incrementAndGet();
            if (count <= 3 || count % DEBUG_LOG_INTERVAL == 0) {
                LOGGER.debug("{} [PIPE] Camera.setup override applied#={} eye=({},{},{}) yaw={} pitch={}",
                        CaptureConstants.LOG_MARKER, count, pose.eyePosition().x, pose.eyePosition().y,
                        pose.eyePosition().z, pose.spec().yawDegrees(), pose.spec().pitchDegrees());
            }
        }
    }
}
