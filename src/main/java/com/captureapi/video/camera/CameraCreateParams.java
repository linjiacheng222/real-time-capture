package com.captureapi.video.camera;

import java.util.Objects;
import java.util.UUID;
import org.joml.Vector3d;

/**
 * 创建/调整相机时的参数集（I2.1.1）。
 *
 * <p>纯 Java 值对象，可跨线程传递；不含任何 MC 对象引用。{@link CameraManager#create} 使用本类，
 * {@link CameraManager#adjust} 也使用本类做整体替换（绑定实体与绝对位置可互切）。</p>
 */
public final class CameraCreateParams {

    /** API 默认 FOV（未指定时使用；非全局配置，相机参数走运行时 API）。 */
    public static final double DEFAULT_FOV_DEGREES = 70.0;

    /** 默认投影形状 id（T2.1.4 注册的标准透视）。 */
    public static final String DEFAULT_PROJECTION_ID = "rect";

    private final double x;
    private final double y;
    private final double z;
    private final float yawDegrees;
    private final float pitchDegrees;
    private final float rollDegrees;
    private final double fovDegrees;
    private final int width;
    private final int height;
    private final UUID boundEntityUuid;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final String projectionId;

    private CameraCreateParams(Builder builder) {
        this.x = builder.x;
        this.y = builder.y;
        this.z = builder.z;
        this.yawDegrees = builder.yawDegrees;
        this.pitchDegrees = builder.pitchDegrees;
        this.rollDegrees = builder.rollDegrees;
        this.fovDegrees = builder.fovDegrees;
        this.width = builder.width;
        this.height = builder.height;
        this.boundEntityUuid = builder.boundEntityUuid;
        this.offsetX = builder.offsetX;
        this.offsetY = builder.offsetY;
        this.offsetZ = builder.offsetZ;
        this.projectionId = Objects.requireNonNull(builder.projectionId, "projectionId");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return 绝对位置 X */
    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yawDegrees() {
        return yawDegrees;
    }

    public float pitchDegrees() {
        return pitchDegrees;
    }

    public float rollDegrees() {
        return rollDegrees;
    }

    public double fovDegrees() {
        return fovDegrees;
    }

    /** @return 相机输出宽（0=使用全局 video_resolution 配置） */
    public int width() {
        return width;
    }

    /** @return 相机输出高（0=使用全局 video_resolution 配置） */
    public int height() {
        return height;
    }

    /** @return 绑定实体 UUID；null 表示使用绝对位置 */
    public UUID boundEntityUuid() {
        return boundEntityUuid;
    }

    public double offsetX() {
        return offsetX;
    }

    public double offsetY() {
        return offsetY;
    }

    public double offsetZ() {
        return offsetZ;
    }

    public String projectionId() {
        return projectionId;
    }

    /** @return 绝对位置（仅用于日志/调试） */
    public Vector3d position() {
        return new Vector3d(x, y, z);
    }

    /** @return 绑定偏移（仅用于日志/调试） */
    public Vector3d boundOffset() {
        return new Vector3d(offsetX, offsetY, offsetZ);
    }

    /** 建造器（不可变参数集的唯一构造入口）。 */
    public static final class Builder {
        private double x;
        private double y;
        private double z;
        private float yawDegrees;
        private float pitchDegrees;
        private float rollDegrees;
        private double fovDegrees = DEFAULT_FOV_DEGREES;
        private int width;
        private int height;
        private UUID boundEntityUuid;
        private double offsetX;
        private double offsetY;
        private double offsetZ;
        private String projectionId = DEFAULT_PROJECTION_ID;

        public Builder position(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Builder rotation(float yawDegrees, float pitchDegrees, float rollDegrees) {
            this.yawDegrees = yawDegrees;
            this.pitchDegrees = pitchDegrees;
            this.rollDegrees = rollDegrees;
            return this;
        }

        public Builder fov(double fovDegrees) {
            this.fovDegrees = fovDegrees;
            return this;
        }

        public Builder resolution(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder boundEntity(UUID entityUuid, double offsetX, double offsetY, double offsetZ) {
            this.boundEntityUuid = entityUuid;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            return this;
        }

        public Builder projectionId(String projectionId) {
            this.projectionId = projectionId;
            return this;
        }

        public CameraCreateParams build() {
            return new CameraCreateParams(this);
        }
    }
}
