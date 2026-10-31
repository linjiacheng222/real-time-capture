package com.captureapi.video.camera;

import java.util.Objects;
import java.util.UUID;
import org.joml.Vector3d;

/**
 * 相机不可变值对象（I2.1.1）。
 *
 * <p>纯 Java 数据（不含 MC 对象引用），可跨线程传递；参数变更走「新快照整体替换」而非原地修改
 * （I2.1.3）。{@code position} 在绑定实体时作为回退位置（实体卸载时使用最后已知位置）。</p>
 *
 * <p>id=0 保留给实时调取的临时相机（不进 CameraManager、不占自增 id）。</p>
 *
 * <p>角度约定：yaw/pitch/roll 单位为度；yaw 0=朝 +Z（南），yaw 90=朝 -X（西），pitch 正=向下，
 * roll 绕视线方向旋转。</p>
 */
public record CameraSpec(
        int id,
        double positionX,
        double positionY,
        double positionZ,
        float yawDegrees,
        float pitchDegrees,
        float rollDegrees,
        double fovDegrees,
        int width,
        int height,
        UUID boundEntityUuid,
        double boundOffsetX,
        double boundOffsetY,
        double boundOffsetZ,
        String projectionId,
        boolean paused) {

    public CameraSpec {
        if (id < 0) {
            throw new IllegalArgumentException("相机 id 不可为负：id=" + id);
        }
        if (!Double.isFinite(positionX) || !Double.isFinite(positionY) || !Double.isFinite(positionZ)) {
            throw new IllegalArgumentException("相机位置必须为有限数");
        }
        if (fovDegrees <= 1.0D || fovDegrees >= 179.0D) {
            throw new IllegalArgumentException("FOV 必须在 (1,179) 度内：fov=" + fovDegrees);
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("相机分辨率必须为正：width=" + width + ", height=" + height);
        }
        Objects.requireNonNull(projectionId, "projectionId");
        if (projectionId.isBlank()) {
            throw new IllegalArgumentException("projectionId 不能为空");
        }
    }

    /** @return 绝对/回退位置 */
    public Vector3d position() {
        return new Vector3d(positionX, positionY, positionZ);
    }

    /** @return 绑定实体偏移 */
    public Vector3d boundOffset() {
        return new Vector3d(boundOffsetX, boundOffsetY, boundOffsetZ);
    }

    /** 返回暂停状态翻转后的新快照（不可变整体替换）。 */
    public CameraSpec withPaused(boolean newPaused) {
        return new CameraSpec(id, positionX, positionY, positionZ, yawDegrees, pitchDegrees, rollDegrees,
                fovDegrees, width, height, boundEntityUuid, boundOffsetX, boundOffsetY, boundOffsetZ,
                projectionId, newPaused);
    }

    /** 返回参数整体替换后的新快照（保留 id 与暂停状态）。 */
    public CameraSpec withParams(CameraCreateParams params, int resolvedWidth, int resolvedHeight) {
        return new CameraSpec(id, params.x(), params.y(), params.z(), params.yawDegrees(),
                params.pitchDegrees(), params.rollDegrees(), params.fovDegrees(), resolvedWidth, resolvedHeight,
                params.boundEntityUuid(), params.offsetX(), params.offsetY(), params.offsetZ(),
                params.projectionId(), paused);
    }
}
