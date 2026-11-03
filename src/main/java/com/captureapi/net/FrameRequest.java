package com.captureapi.net;

import com.captureapi.video.camera.CameraCreateParams;
import com.captureapi.video.camera.CameraSpec;
import com.captureapi.video.overlay.OverlayModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 统一帧获取参数（T4.9 / I4.9.1，不可变对象）。
 *
 * <p>省略参数使用 active 相机/全局默认；显式位置与绑定实体互斥；temporary 与 continuous 互斥。
 * 复用 T4.7 grabFrame/最新帧总线，不新造数据源。</p>
 */
public final class FrameRequest {

    public enum OutputMethod {
        FILE,
        HTTP
    }

    public enum Format {
        JPEG,
        PNG
    }

    private final OutputMethod outputMethod;
    private final Format format;
    private final Path outputPath;
    private final boolean temporary;
    private final boolean continuous;
    private final long timeoutMillis;
    private final boolean hasExplicitCamera;
    private final boolean rotationSet;
    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final float roll;
    private final double fov;
    private final int width;
    private final int height;
    private final int quality;
    private final UUID boundEntity;
    private final boolean includeAudio;
    private final int audioChunks;
    private final String filter;
    private final List<OverlayModel> overlays;
    private final Integer cameraId;

    private FrameRequest(Builder builder) {
        this.outputMethod = builder.outputMethod;
        this.format = builder.format;
        this.outputPath = builder.outputPath;
        this.temporary = builder.temporary;
        this.continuous = builder.continuous;
        this.timeoutMillis = builder.timeoutMillis;
        this.rotationSet = builder.rotationSet;
        this.hasExplicitCamera = builder.positionSet || builder.boundEntity != null || builder.fovSet
                || builder.widthSet || builder.rotationSet;
        this.x = builder.x;
        this.y = builder.y;
        this.z = builder.z;
        this.yaw = builder.yaw;
        this.pitch = builder.pitch;
        this.roll = builder.roll;
        this.fov = builder.fov;
        this.width = builder.width;
        this.height = builder.height;
        this.quality = builder.quality;
        this.boundEntity = builder.boundEntity;
        this.includeAudio = builder.includeAudio;
        this.audioChunks = builder.audioChunks;
        this.filter = builder.filter;
        this.overlays = builder.overlays == null ? null : Collections.unmodifiableList(new ArrayList<>(builder.overlays));
        this.cameraId = builder.cameraId;
        if (builder.positionSet && builder.boundEntity != null) {
            throw new IllegalArgumentException("position 与 boundEntity 互斥，不能同时指定");
        }
        if (builder.temporary && builder.continuous) {
            throw new IllegalArgumentException("temporary=true 与 continuous=true 语义冲突");
        }
        if (builder.timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis 必须 > 0");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public OutputMethod outputMethod() {
        return outputMethod;
    }

    public Format format() {
        return format;
    }

    public Path outputPath() {
        return outputPath;
    }

    public boolean temporary() {
        return temporary;
    }

    public boolean continuous() {
        return continuous;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    public boolean hasExplicitCamera() {
        return hasExplicitCamera;
    }

    public int quality() {
        return quality;
    }

    public boolean includeAudio() {
        return includeAudio;
    }

    public int audioChunks() {
        return audioChunks;
    }

    /** @return 滤镜覆盖（null=不覆盖沿用全局；空串=关闭滤镜；非空=选中该滤镜） */
    public String filter() {
        return filter;
    }

    /** @return 叠加覆盖（null=不覆盖沿用全局；空列表=清空；非空=替换为该列表） */
    public List<OverlayModel> overlays() {
        return overlays;
    }

    /** @return 指定已存在相机 id；null=active/临时参数路径 */
    public Integer cameraId() {
        return cameraId;
    }

    /**
     * 映射为临时/当前相机参数（省略字段从 active 相机补齐）。
     *
     * @param active 当前 active 相机（可为 null）
     * @return CameraCreateParams
     */
    public CameraCreateParams toCameraParams(CameraSpec active) {
        CameraCreateParams.Builder builder = CameraCreateParams.builder();
        if (boundEntity != null) {
            builder.boundEntity(boundEntity, 0.0D, 0.0D, 0.0D);
            if (active != null) {
                builder.position(active.positionX(), active.positionY(), active.positionZ());
            }
        } else if (hasExplicitCamera) {
            builder.position(x, y, z);
        } else if (active != null) {
            builder.position(active.positionX(), active.positionY(), active.positionZ());
        }
        if (active != null) {
            builder.rotation(rotationSet ? yaw : active.yawDegrees(),
                    rotationSet ? pitch : active.pitchDegrees(),
                    rotationSet ? roll : active.rollDegrees());
            builder.fov(fov > 0.0D ? fov : active.fovDegrees());
            builder.resolution(width > 0 ? width : active.width(), height > 0 ? height : active.height());
            if (active.boundEntityUuid() != null && boundEntity == null) {
                // 不回退绑定实体：显式帧获取默认仍使用 active 的绝对位置
                builder.position(active.positionX(), active.positionY(), active.positionZ());
            }
        } else {
            builder.rotation(yaw, pitch, roll);
            if (fov > 0.0D) {
                builder.fov(fov);
            }
            if (width > 0 && height > 0) {
                builder.resolution(width, height);
            }
        }
        return builder.build();
    }

    /** 参数构建器。 */
    public static final class Builder {
        private OutputMethod outputMethod = OutputMethod.FILE;
        private Format format = Format.JPEG;
        private Path outputPath;
        private boolean temporary;
        private boolean continuous;
        private long timeoutMillis = 5000L;
        private boolean positionSet;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;
        private float roll;
        private boolean rotationSet;
        private boolean fovSet;
        private double fov;
        private boolean widthSet;
        private int width;
        private int height;
        private int quality = -1;
        private UUID boundEntity;
        private boolean includeAudio;
        private int audioChunks = 1;
        private String filter;
        private List<OverlayModel> overlays;
        private Integer cameraId;

        public Builder outputMethod(OutputMethod outputMethod) {
            this.outputMethod = outputMethod;
            return this;
        }

        public Builder format(Format format) {
            this.format = format;
            return this;
        }

        public Builder outputPath(Path outputPath) {
            this.outputPath = outputPath;
            return this;
        }

        public Builder position(double x, double y, double z) {
            this.positionSet = true;
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Builder rotation(float yaw, float pitch, float roll) {
            this.rotationSet = true;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            return this;
        }

        public Builder fov(double fov) {
            this.fovSet = true;
            this.fov = fov;
            return this;
        }

        public Builder resolution(int width, int height) {
            this.widthSet = true;
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder quality(int quality) {
            this.quality = quality;
            return this;
        }

        public Builder boundEntity(UUID uuid) {
            this.boundEntity = uuid;
            return this;
        }

        public Builder temporary(boolean temporary) {
            this.temporary = temporary;
            return this;
        }

        public Builder continuous(boolean continuous) {
            this.continuous = continuous;
            return this;
        }

        public Builder timeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public Builder audio(boolean includeAudio, int chunks) {
            this.includeAudio = includeAudio;
            this.audioChunks = chunks;
            return this;
        }

        /** 滤镜覆盖：null=不覆盖；""=关闭；"name"=选中 */
        public Builder filter(String filter) {
            this.filter = filter;
            return this;
        }

        /** 叠加覆盖（null=不覆盖；空列表=清空；非空=替换） */
        public Builder overlays(List<OverlayModel> overlays) {
            this.overlays = overlays;
            return this;
        }

        /** 指定已存在相机（不新建临时相机）。 */
        public Builder cameraId(Integer cameraId) {
            this.cameraId = cameraId;
            return this;
        }

        /** 便捷：单层叠加覆盖 */
        public Builder overlay(OverlayModel overlay) {
            this.overlays = overlay == null ? null : List.of(overlay);
            return this;
        }

        public FrameRequest build() {
            return new FrameRequest(this);
        }
    }
}
