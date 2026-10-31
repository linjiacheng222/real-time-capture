package com.captureapi.video.camera;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 投影形状扩展点（I2.1.4）。
 *
 * <p>只约定「给定相机参数 + 离屏分辨率，产出投影矩阵」单一职责；位置/朝向由 {@link CameraSpec} 统一承载，
 * 形状实现（如 T4.8 球面锥）只填充投影矩阵，不改相机核心。M2 仅实现并注册 {@code "rect"}（标准透视）。</p>
 */
@OnlyIn(Dist.CLIENT)
public interface ViewProjection {

    /** @return 形状 id（注册表键；对应 API 参数 {@code shape}） */
    String id();

    /**
     * 计算投影矩阵。
     *
     * @param spec       相机快照
     * @param width      离屏宽
     * @param height     离屏高
     * @param nearPlane  近裁剪
     * @param farPlane   远裁剪
     * @return 投影矩阵
     */
    Matrix4f projectionMatrix(CameraSpec spec, int width, int height, double nearPlane, double farPlane);
}
