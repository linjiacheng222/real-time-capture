package com.captureapi.video.overlay;

import com.captureapi.core.constant.CaptureConstants;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P2.4 GPU 叠加 v1：在离屏 FBO 上按图层直接绘制纹理四边形，然后回读。
 *
 * <p>通过 JVM 属性 {@code -Dcaptureapi.overlay.gpu=true} 启用；默认仍走 CPU
 * {@link OverlayRegistry#apply}。启用时 CPU 路径自动跳过，避免双重合成。</p>
 *
 * <p>限制：纹理按贴图名缓存，overlay.reload 后旧纹理不立即删除（进程级泄漏量可控）；
 * 仅矩形贴图；不进入主画面。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class GpuOverlayRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final boolean ENABLED = Boolean.getBoolean("captureapi.overlay.gpu");
    private static final Map<String, Integer> TEXTURES = new HashMap<>();

    static {
        if (ENABLED) {
            LOGGER.info("{} [OVERLAY/GPU] GPU 叠加已启用（JVM 属性 captureapi.overlay.gpu=true）",
                    CaptureConstants.LOG_MARKER);
        }
    }

    private GpuOverlayRenderer() {
        throw new AssertionError("no instances");
    }

    /** @return 是否启用 GPU 叠加（JVM 属性） */
    public static boolean isEnabled() {
        return ENABLED;
    }

    /** 清除纹理缓存映射（reload 后旧 GL 纹理延迟到进程退出回收）。 */
    public static synchronized void invalidate() {
        TEXTURES.clear();
    }

    /**
     * 把当前图层绘制到当前绑定的 FBO（渲染线程；FBO 分辨率 width×height）。
     *
     * @param width  FBO 宽
     * @param height FBO 高
     */
    public static void render(int width, int height) {
        if (!ENABLED || !OverlayRegistry.get().active()) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        Matrix4f projection = new Matrix4f().setOrtho(0.0F, width, height, 0.0F, -1000.0F, 1000.0F);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        PoseStack modelView = RenderSystem.getModelViewStack();
        modelView.pushPose();
        modelView.setIdentity();
        RenderSystem.applyModelViewMatrix();
        try {
            for (OverlayModel layer : OverlayRegistry.get().layers()) {
                BufferedImage image = OverlayRegistry.get().image(layer.image());
                if (image == null) {
                    continue;
                }
                int texture = texture(layer.image(), image);
                if (texture == 0) {
                    continue;
                }
                RenderSystem.setShaderTexture(0, texture);
                drawLayer(width, height, image, layer);
            }
        } catch (Throwable t) {
            LOGGER.warn("{} [OVERLAY/GPU] 合成失败，本帧跳过（CPU 回退请关闭 GPU 属性）：{}",
                    CaptureConstants.LOG_MARKER, t.toString());
        } finally {
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static void drawLayer(int width, int height, BufferedImage image, OverlayModel layer) {
        double centerX = width * layer.x();
        double centerY = height * layer.y();
        double halfW = image.getWidth() * layer.scaleX() / 2.0D;
        double halfH = image.getHeight() * layer.scaleY() / 2.0D;
        double radians = Math.toRadians(layer.rotation());
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        float alpha = (float) Math.max(0.0D, Math.min(1.0D, layer.opacity()));

        double[][] corners = {
                {-halfW, -halfH}, {halfW, -halfH}, {halfW, halfH}, {-halfW, halfH}
        };
        float[][] uvs = {{0.0F, 0.0F}, {1.0F, 0.0F}, {1.0F, 1.0F}, {0.0F, 1.0F}};
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        Matrix4f matrix = new Matrix4f();
        for (int i = 0; i < corners.length; i++) {
            double dx = corners[i][0];
            double dy = corners[i][1];
            double x = centerX + dx * cos - dy * sin;
            double y = centerY + dx * sin + dy * cos;
            buffer.vertex(matrix, (float) x, (float) y, 0.0F)
                    .uv(uvs[i][0], uvs[i][1])
                    .color(1.0F, 1.0F, 1.0F, alpha)
                    .endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
    }

    private static synchronized int texture(String name, BufferedImage image) {
        Integer cached = TEXTURES.get(name);
        if (cached != null) {
            return cached;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder());
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int argb = row[x];
                pixels.put((byte) ((argb >> 16) & 0xFF));
                pixels.put((byte) ((argb >> 8) & 0xFF));
                pixels.put((byte) (argb & 0xFF));
                pixels.put((byte) ((argb >> (Integer.SIZE - Byte.SIZE)) & 0xFF));
            }
        }
        pixels.flip();
        int texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        TEXTURES.put(name, texture);
        LOGGER.info("{} [OVERLAY/GPU] 纹理上传：{}（{}x{}，id={}）", CaptureConstants.LOG_MARKER,
                name, width, height, texture);
        return texture;
    }
}
