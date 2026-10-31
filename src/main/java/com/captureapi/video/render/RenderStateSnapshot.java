package com.captureapi.video.render;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.thread.GlThreadGuard;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离屏渲染状态保存/恢复（I2.3.1）。
 *
 * <p>离屏渲染前后保存并恢复 GL/MC 状态，保证主画面零扰动。保存集（M2 首版，W-032 待实装核对）：</p>
 * <ul>
 *   <li>GL：FBO 绑定、viewport、clear color、深度/混合/裁剪开关、活动纹理单元与 2D 纹理绑定、shader program；</li>
 *   <li>MC：投影矩阵、shader 实例、雾起止/颜色/形状。</li>
 * </ul>
 * <p>PoseStack 由离屏流程使用局部栈，不改全局；相机对象由 {@link CameraStateSnapshot} 单独保存/恢复。
 * 所有调用仅限渲染主线程。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RenderStateSnapshot {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private final int framebufferBinding;
    private final int viewportX;
    private final int viewportY;
    private final int viewportWidth;
    private final int viewportHeight;
    private final float clearRed;
    private final float clearGreen;
    private final float clearBlue;
    private final float clearAlpha;
    private final boolean depthTest;
    private final boolean blend;
    private final boolean cull;
    private final int activeTexture;
    private final int textureBinding2d;
    private final int program;
    private final ShaderInstance shader;
    private final Matrix4f projectionMatrix;
    private final float fogStart;
    private final float fogEnd;
    private final float[] fogColor;
    private final FogShape fogShape;

    private RenderStateSnapshot(int framebufferBinding, int viewportX, int viewportY, int viewportWidth,
            int viewportHeight, float clearRed, float clearGreen, float clearBlue, float clearAlpha,
            boolean depthTest, boolean blend, boolean cull, int activeTexture, int textureBinding2d, int program,
            ShaderInstance shader, Matrix4f projectionMatrix, float fogStart, float fogEnd, float[] fogColor,
            FogShape fogShape) {
        this.framebufferBinding = framebufferBinding;
        this.viewportX = viewportX;
        this.viewportY = viewportY;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.clearRed = clearRed;
        this.clearGreen = clearGreen;
        this.clearBlue = clearBlue;
        this.clearAlpha = clearAlpha;
        this.depthTest = depthTest;
        this.blend = blend;
        this.cull = cull;
        this.activeTexture = activeTexture;
        this.textureBinding2d = textureBinding2d;
        this.program = program;
        this.shader = shader;
        this.projectionMatrix = projectionMatrix;
        this.fogStart = fogStart;
        this.fogEnd = fogEnd;
        this.fogColor = fogColor;
        this.fogShape = fogShape;
    }

    /**
     * 捕获当前渲染状态（渲染线程）。
     *
     * @return 状态快照
     */
    public static RenderStateSnapshot capture() {
        GlThreadGuard.checkGlThread("RenderStateSnapshot.capture");
        int fbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        float[] clear = new float[4];
        int[] viewport = new int[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer vp = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            viewport[0] = vp.get(0);
            viewport[1] = vp.get(1);
            viewport[2] = vp.get(2);
            viewport[3] = vp.get(3);
            FloatBuffer cc = stack.mallocFloat(4);
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, cc);
            clear[0] = cc.get(0);
            clear[1] = cc.get(1);
            clear[2] = cc.get(2);
            clear[3] = cc.get(3);
        }
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int texture2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int program = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int[] view = viewport;
        return new RenderStateSnapshot(fbo, view[0], view[1], view[2], view[3],
                clear[0], clear[1], clear[2], clear[3],
                GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GL11.glIsEnabled(GL11.GL_BLEND),
                GL11.glIsEnabled(GL11.GL_CULL_FACE), activeTexture, texture2d, program,
                RenderSystem.getShader(), new Matrix4f(RenderSystem.getProjectionMatrix()),
                RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                RenderSystem.getShaderFogColor().clone(), RenderSystem.getShaderFogShape());
    }

    /**
     * 恢复保存的渲染状态（渲染线程）。
     */
    public void restore() {
        GlThreadGuard.checkGlThread("RenderStateSnapshot.restore");
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebufferBinding);
        GlStateManager._viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        GlStateManager._clearColor(clearRed, clearGreen, clearBlue, clearAlpha);
        if (depthTest) {
            RenderSystem.enableDepthTest();
        } else {
            RenderSystem.disableDepthTest();
        }
        if (blend) {
            RenderSystem.enableBlend();
        } else {
            RenderSystem.disableBlend();
        }
        if (cull) {
            RenderSystem.enableCull();
        } else {
            RenderSystem.disableCull();
        }
        GlStateManager._activeTexture(activeTexture);
        GlStateManager._bindTexture(textureBinding2d);
        GlStateManager._glUseProgram(program);
        if (shader != null) {
            RenderSystem.setShader(() -> shader);
        }
        RenderSystem.setProjectionMatrix(new Matrix4f(projectionMatrix), VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        RenderSystem.setShaderFogShape(fogShape);
        if (CaptureParams.get().logResourceLifecycle() && LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} 渲染状态已恢复（fbo={}，viewport={}x{}，program={}）",
                    CaptureConstants.LOG_MARKER, framebufferBinding, viewportWidth, viewportHeight, program);
        }
    }
}
