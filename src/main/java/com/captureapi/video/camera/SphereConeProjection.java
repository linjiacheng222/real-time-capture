package com.captureapi.video.camera;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 球面锥投影（T4.8 / I4.8.3）。
 *
 * <p>既定方案：锥角由 FOV 表达、母线长度由远裁剪承载，使用标准透视矩阵生成，不写自定义几何裁剪
 * （离屏管线零改动）。轴方向默认随相机朝向；独立轴方向当前仅存参数不参与矩阵（留扩展位）。</p>
 *
 * <p>注意：球面锥“弧形底面”的视觉确认归 I4.8.4 测试用例；标准透视矩阵的远平面为平面，若视觉不符
 * 预期需上呈，不在实现层自行解释。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class SphereConeProjection implements ViewProjection {

    /** 形状 id（协议/API 使用）。 */
    public static final String ID = ShapeModel.SPHERE_CONE;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Matrix4f projectionMatrix(CameraSpec spec, int width, int height, double nearPlane, double farPlane) {
        ShapeModel shape = spec.shape();
        if (!shape.isSphereCone()) {
            throw new IllegalArgumentException("非球面锥相机不能使用 SphereConeProjection");
        }
        float angle = (float) Math.toRadians(shape.angleDegrees());
        float aspect = (float) width / (float) height;
        return new Matrix4f().perspective(angle, aspect, (float) nearPlane, (float) shape.axisLength());
    }
}
