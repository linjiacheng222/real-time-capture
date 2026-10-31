package com.captureapi.video.camera;

import java.util.Objects;

/**
 * 视野形状参数模型（T4.8 / I4.8.1）。
 *
 * <p>矩形 {@link #RECT}：无额外参数（沿用相机 FOV + 离屏宽高比）；
 * 球面锥 {@link #SPHERE_CONE}：开角（度，∈(0,180)）＋母线/轴长（格，>0），
 * 轴方向默认随相机朝向（独立轴方向留扩展位，当前不参与矩阵）。
 * 参数随 {@link CameraSpec} 快照流转（线程安全由不可变对象保证）。</p>
 */
public final class ShapeModel {

    /** 矩形（默认）。 */
    public static final String RECT = "rect";

    /** 球面锥。 */
    public static final String SPHERE_CONE = "sphere_cone";

    private static final ShapeModel RECT_MODEL = new ShapeModel(RECT, 0.0D, 0.0D, 0.0F, 0.0F);

    private final String type;
    private final double angleDegrees;
    private final double axisLength;
    private final float axisYaw;
    private final float axisPitch;

    private ShapeModel(String type, double angleDegrees, double axisLength, float axisYaw, float axisPitch) {
        this.type = Objects.requireNonNull(type, "type");
        this.angleDegrees = angleDegrees;
        this.axisLength = axisLength;
        this.axisYaw = axisYaw;
        this.axisPitch = axisPitch;
    }

    public static ShapeModel rect() {
        return RECT_MODEL;
    }

    /**
     * 构造球面锥（参数不足/越界即抛 {@link IllegalArgumentException}，字段名+范围可编程修正）。
     *
     * @param angleDegrees 开角（度）
     * @param axisLength   母线/轴长（格）
     * @param axisYaw      独立轴方向 yaw（度；默认随相机，当前不参与矩阵）
     * @param axisPitch    独立轴方向 pitch（度）
     * @return 球面锥模型
     */
    public static ShapeModel sphereCone(double angleDegrees, double axisLength, float axisYaw, float axisPitch) {
        if (!Double.isFinite(angleDegrees) || angleDegrees <= 0.0D || angleDegrees >= 180.0D) {
            throw new IllegalArgumentException("shape.angle 必须在 (0,180) 度内：angle=" + angleDegrees);
        }
        if (!Double.isFinite(axisLength) || axisLength <= 0.0D) {
            throw new IllegalArgumentException("shape.axisLength 必须 > 0：axisLength=" + axisLength);
        }
        if (!Float.isFinite(axisYaw) || !Float.isFinite(axisPitch)) {
            throw new IllegalArgumentException("shape.axisYaw/axisPitch 必须为有限数");
        }
        if (axisPitch < -90.0F || axisPitch > 90.0F) {
            throw new IllegalArgumentException("shape.axisPitch 必须在 [-90,90]：axisPitch=" + axisPitch);
        }
        return new ShapeModel(SPHERE_CONE, angleDegrees, axisLength, axisYaw, axisPitch);
    }

    /**
     * 按投影 id 与参数构造（供指令/门面统一入口；rect 忽略参数）。
     *
     * @param type         shape id
     * @param angleDegrees 开角（cone）
     * @param axisLength   轴长（cone）
     * @param axisYaw      轴 yaw（cone）
     * @param axisPitch    轴 pitch（cone）
     * @return 形状模型
     */
    public static ShapeModel of(String type, double angleDegrees, double axisLength, float axisYaw, float axisPitch) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("shape 不能为空");
        }
        if (RECT.equals(type)) {
            return RECT_MODEL;
        }
        if (SPHERE_CONE.equals(type)) {
            return sphereCone(angleDegrees, axisLength, axisYaw, axisPitch);
        }
        throw new IllegalArgumentException("未知形状：" + type + "（支持 rect/sphere_cone）");
    }

    public String type() {
        return type;
    }

    public boolean isSphereCone() {
        return SPHERE_CONE.equals(type);
    }

    public double angleDegrees() {
        return angleDegrees;
    }

    public double axisLength() {
        return axisLength;
    }

    public float axisYaw() {
        return axisYaw;
    }

    public float axisPitch() {
        return axisPitch;
    }
}
