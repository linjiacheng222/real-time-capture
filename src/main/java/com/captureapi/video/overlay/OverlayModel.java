package com.captureapi.video.overlay;

/**
 * 叠加层 2D 参数模型（T8.2 / I8.2.2）。
 *
 * @param image     贴图名（overlay_directory 下 PNG 文件名）
 * @param x         归一化中心 X（0~1 相对帧宽）
 * @param y         归一化中心 Y（0~1 相对帧高）
 * @param scaleX    X 缩放
 * @param scaleY    Y 缩放
 * @param rotation  旋转角度（度，绕中心）
 * @param opacity   透明度 0~1
 */
public record OverlayModel(String image, double x, double y, double scaleX, double scaleY,
        double rotation, double opacity) {

    public OverlayModel {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("叠加贴图名不能为空");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("叠加位置必须为有限数");
        }
        if (!Double.isFinite(scaleX) || !Double.isFinite(scaleY) || scaleX <= 0.0D || scaleY <= 0.0D) {
            throw new IllegalArgumentException("叠加缩放必须 > 0");
        }
        if (!Double.isFinite(rotation)) {
            throw new IllegalArgumentException("叠加旋转必须为有限数");
        }
        if (opacity < 0.0D || opacity > 1.0D) {
            throw new IllegalArgumentException("叠加透明度必须在 [0,1]");
        }
    }
}
