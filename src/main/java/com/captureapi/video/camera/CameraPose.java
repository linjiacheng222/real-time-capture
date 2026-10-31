package com.captureapi.video.camera;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * 单帧相机位姿与矩阵快照（I2.1.2 / I2.1.3）。
 *
 * <p>渲染线程帧首由 {@link CameraMatrices#capture(CameraSpec, float)} 生成一次，整帧使用同一实例；
 * 不可变，可安全传给同帧的 M3 监听器同步（同一快照保证画面与声音方向一致）。</p>
 *
 * @param spec            生成该快照的相机参数
 * @param eyePosition     解析后的世界坐标眼位（绑定实体时含插值与偏移）
 * @param viewMatrix      视图矩阵（世界→相机）
 * @param projectionMatrix 投影矩阵（相机→裁剪）
 * @param nearPlane       实际近裁剪
 * @param farPlane        实际远裁剪
 */
@OnlyIn(Dist.CLIENT)
public record CameraPose(
        CameraSpec spec,
        Vector3d eyePosition,
        Matrix4f viewMatrix,
        Matrix4f projectionMatrix,
        double nearPlane,
        double farPlane) {

    public CameraPose {
        if (eyePosition == null || viewMatrix == null || projectionMatrix == null) {
            throw new IllegalArgumentException("CameraPose 组件不可为 null");
        }
    }

    /** @return 视线方向（由快照角度推导；供 T2.7/M3 复用） */
    public Vector3d lookDirection() {
        double yaw = Math.toRadians(spec.yawDegrees());
        double pitch = Math.toRadians(spec.pitchDegrees());
        double cosPitch = Math.cos(pitch);
        return new Vector3d(-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch);
    }
}
