package com.captureapi.video.camera;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.mixin.ClientLevelAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渲染线程内把 {@link CameraSpec} 解析为相机位姿与视图/投影矩阵（I2.1.2 / I2.1.3）。
 *
 * <p>仅应在渲染主线程调用；纯数学计算不触碰 GL。绑定实体时按实体插值位置 + 偏移解析；
 * 实体已卸载/死亡时回退到 spec 的最后已知位置（不崩溃，debug 日志）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CameraMatrices {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private CameraMatrices() {
        throw new AssertionError("no instances");
    }

    /**
     * 帧首捕获一次相机快照与矩阵。
     *
     * @param spec        相机快照（来自 {@link CameraManager#active()}）
     * @param partialTick AFTER_LEVEL 回调提供的帧插值因子
     * @return 帧内不可变位姿快照
     */
    public static CameraPose capture(CameraSpec spec, float partialTick) {
        Vector3d eye = resolveEyePosition(spec, partialTick);
        CaptureParams params = CaptureParams.get();
        double nearPlane = params.nearPlane();
        double farPlane = resolveFarPlane(params.farPlane());
        Matrix4f view = viewMatrix(eye, spec.yawDegrees(), spec.pitchDegrees(), spec.rollDegrees());
        ViewProjection projection = ViewProjectionRegistry.get(spec.projectionId());
        Matrix4f projectionMatrix = projection.projectionMatrix(spec, spec.width(), spec.height(), nearPlane, farPlane);
        return new CameraPose(spec, eye, view, projectionMatrix, nearPlane, farPlane);
    }

    /**
     * 解析眼位：绑定实体 → 插值位置 + 偏移；否则 → 绝对位置；实体缺失时回退最后已知位置。
     *
     * @param spec        相机参数
     * @param partialTick 插值因子
     * @return 世界坐标眼位
     */
    public static Vector3d resolveEyePosition(CameraSpec spec, float partialTick) {
        if (spec.boundEntityUuid() == null) {
            return spec.position();
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            Entity entity = ((ClientLevelAccessor) level).captureapi$getEntities().get(spec.boundEntityUuid());
            if (entity != null) {
                Vec3 interpolated = entity.getPosition(partialTick);
                return new Vector3d(interpolated.x + spec.boundOffsetX(),
                        interpolated.y + spec.boundOffsetY(),
                        interpolated.z + spec.boundOffsetZ());
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} 绑定实体不可用，回退最后已知位置：camera={}，entity={}",
                    CaptureConstants.LOG_MARKER, spec.id(), spec.boundEntityUuid());
        }
        return spec.position();
    }

    /**
     * 由 yaw/pitch/roll + 眼位构造视图矩阵。
     *
     * <p>角度约定：yaw 0=+Z（南）、90=-X（西）；pitch 正=向下；roll 绕视线方向。</p>
     *
     * @param eye         眼位
     * @param yawDegrees  偏航
     * @param pitchDegrees 俯仰
     * @param rollDegrees 滚转
     * @return 视图矩阵（世界→相机）
     */
    public static Matrix4f viewMatrix(Vector3d eye, float yawDegrees, float pitchDegrees, float rollDegrees) {
        Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(-yawDegrees))
                .rotateX((float) Math.toRadians(pitchDegrees))
                .rotateZ((float) Math.toRadians(rollDegrees));
        return new Matrix4f()
                .rotation(rotation)
                .invert()
                .translate((float) -eye.x, (float) -eye.y, (float) -eye.z);
    }

    /** 远裁剪：配置 >0 时显式覆盖；否则按渲染距离×16 动态计算。 */
    private static double resolveFarPlane(double configured) {
        if (configured > 0.0D) {
            return configured;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options != null) {
            return minecraft.options.renderDistance().get() * 16.0D;
        }
        return 256.0D;
    }
}
