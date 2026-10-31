package com.captureapi.video.render;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.mixin.CameraAccessor;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.video.camera.CameraPose;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原版相机状态快照与临时替换（I2.3.2）。
 *
 * <p>离屏渲染窗口内将 {@code GameRenderer#getMainCamera()} 的位姿字段临时改为自定义机位
 * （{@link CameraPose}），渲染后调用 {@link #restore(Camera)} 完整恢复。保存/恢复覆盖：
 * position、xRot/yRot、rotation、forwards/up/left、initialized、detached、level、entity。</p>
 *
 * <p>窗口仅限单次离屏渲染；渲染线程内使用。注意 {@code GameRenderer#renderLevel} 结束时会
 * 调用 {@code mainCamera.reset()}，因此恢复必须在调用返回后立即执行。</p>
 */
@OnlyIn(Dist.CLIENT)
public record CameraStateSnapshot(
        Vec3 position,
        float xRot,
        float yRot,
        Quaternionf rotation,
        Vector3f forwards,
        Vector3f up,
        Vector3f left,
        boolean initialized,
        boolean detached,
        BlockGetter level,
        Entity entity) {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    public CameraStateSnapshot {
        position = position == null ? Vec3.ZERO : position;
        rotation = rotation == null ? new Quaternionf() : new Quaternionf(rotation);
        forwards = forwards == null ? new Vector3f(0.0F, 0.0F, 1.0F) : new Vector3f(forwards);
        up = up == null ? new Vector3f(0.0F, 1.0F, 0.0F) : new Vector3f(up);
        left = left == null ? new Vector3f(1.0F, 0.0F, 0.0F) : new Vector3f(left);
    }

    /**
     * 捕获当前主相机状态。
     *
     * @param camera 原版主相机
     * @return 状态快照
     */
    public static CameraStateSnapshot capture(Camera camera) {
        GlThreadGuard.checkGlThread("CameraStateSnapshot.capture");
        CameraAccessor accessor = (CameraAccessor) camera;
        return new CameraStateSnapshot(accessor.captureapi$getPosition(), accessor.captureapi$getXRot(),
                accessor.captureapi$getYRot(), accessor.captureapi$getRotation(),
                accessor.captureapi$getForwards(), accessor.captureapi$getUp(), accessor.captureapi$getLeft(),
                accessor.captureapi$isInitialized(), accessor.captureapi$isDetached(),
                accessor.captureapi$getLevel(), accessor.captureapi$getEntity());
    }

    /**
     * 应用自定义机位到原版主相机。
     *
     * @param camera 原版主相机
     * @param pose   相机位姿（T2.1.2 矩阵结果）
     * @return 替换前的状态快照（渲染后 restore）
     */
    public static CameraStateSnapshot applyPose(Camera camera, CameraPose pose) {
        GlThreadGuard.checkGlThread("CameraStateSnapshot.applyPose");
        CameraStateSnapshot saved = capture(camera);
        CameraAccessor accessor = (CameraAccessor) camera;
        accessor.captureapi$setPosition(new Vec3(pose.eyePosition().x, pose.eyePosition().y, pose.eyePosition().z));
        // 先走原版 setRotation（同步 forwards/up/left），再叠加 roll
        accessor.captureapi$setRotation(pose.spec().yawDegrees(), pose.spec().pitchDegrees());
        float yawRad = (float) Math.toRadians(-pose.spec().yawDegrees());
        float pitchRad = (float) Math.toRadians(pose.spec().pitchDegrees());
        float rollRad = (float) Math.toRadians(pose.spec().rollDegrees());
        Quaternionf rotation = new Quaternionf().rotationYXZ(yawRad, pitchRad, rollRad);
        accessor.captureapi$getRotation().set(rotation);
        accessor.captureapi$getForwards().set(0.0F, 0.0F, 1.0F).rotate(rotation);
        accessor.captureapi$getUp().set(0.0F, 1.0F, 0.0F).rotate(rotation);
        accessor.captureapi$getLeft().set(1.0F, 0.0F, 0.0F).rotate(rotation);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} 相机临时替换：eye=({},{},{}) yaw={} pitch={} roll={}",
                    CaptureConstants.LOG_MARKER, pose.eyePosition().x, pose.eyePosition().y, pose.eyePosition().z,
                    pose.spec().yawDegrees(), pose.spec().pitchDegrees(), pose.spec().rollDegrees());
        }
        return saved;
    }

    /**
     * 恢复相机状态。
     *
     * @param camera 原版主相机
     */
    public void restore(Camera camera) {
        GlThreadGuard.checkGlThread("CameraStateSnapshot.restore");
        CameraAccessor accessor = (CameraAccessor) camera;
        accessor.captureapi$setPosition(position);
        accessor.captureapi$setXRot(xRot);
        accessor.captureapi$setYRot(yRot);
        accessor.captureapi$getRotation().set(rotation);
        accessor.captureapi$getForwards().set(forwards);
        accessor.captureapi$getUp().set(up);
        accessor.captureapi$getLeft().set(left);
        accessor.captureapi$setInitialized(initialized);
        accessor.captureapi$setDetached(detached);
        accessor.captureapi$setLevel(level);
        accessor.captureapi$setEntity(entity);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} 相机状态已恢复", CaptureConstants.LOG_MARKER);
        }
    }
}
