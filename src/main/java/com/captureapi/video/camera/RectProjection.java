package com.captureapi.video.camera;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 标准矩形透视投影（I2.1.4；注册 id {@code "rect"}，作为 M2 默认）。
 *
 * <p>FOV 按垂直视场角解释（与 MC 透视习惯一致）；宽高比取自离屏分辨率，不跟随主画面。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RectProjection implements ViewProjection {

    /** 形状 id（协议/API 使用）。 */
    public static final String ID = "rect";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Matrix4f projectionMatrix(CameraSpec spec, int width, int height, double nearPlane, double farPlane) {
        float aspect = (float) width / (float) height;
        return new Matrix4f().perspective((float) Math.toRadians(spec.fovDegrees()), aspect,
                (float) nearPlane, (float) farPlane);
    }
}
