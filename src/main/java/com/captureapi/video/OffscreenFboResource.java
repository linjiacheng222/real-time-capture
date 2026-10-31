package com.captureapi.video;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.resource.ManagedGlResource;
import com.captureapi.core.thread.GlThreadGuard;
import com.mojang.blaze3d.pipeline.TextureTarget;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 离屏 FBO 资源（I2.2.2）。
 *
 * <p>经 {@link com.captureapi.core.resource.WorldResourceManager} 注册为世界级 GL 资源：
 * 进世界创建（颜色纹理 + 深度附件），全程复用，退世界销毁；禁止每帧新建（红线 3）。</p>
 *
 * <p>尺寸由 {@code video_resolution} 驱动；配置改变时在下一放行帧统一重建一次（惰性、非每帧）。
 * 所有 GL 操作均在渲染主线程（管理器 + 本类守卫）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class OffscreenFboResource implements ManagedGlResource {

    /** 资源键（T2.2.2 固定键）。 */
    public static final String KEY = CaptureConstants.RESOURCE_KEY_PREFIX + "video:offscreen_fbo";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private TextureTarget target;
    private int width = -1;
    private int height = -1;
    private long rebuildCount;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public void create() {
        if (target == null) {
            ensureTarget();
            LOGGER.info("{} 离屏 FBO 创建：{}x{}，fbo={}，color={}，depth={}",
                    CaptureConstants.LOG_MARKER, width, height, target.frameBufferId,
                    target.getColorTextureId(), target.getDepthTextureId());
        }
    }

    @Override
    public void destroy() {
        if (target != null) {
            target.destroyBuffers();
            LOGGER.info("{} 离屏 FBO 销毁：{}x{}，fbo={}", CaptureConstants.LOG_MARKER,
                    width, height, target.frameBufferId);
            target = null;
            width = -1;
            height = -1;
        }
    }

    /**
     * 获取 FBO（渲染线程；必要时按当前配置分辨率惰性重建）。
     *
     * @return 可绑定的离屏纹理目标
     */
    public TextureTarget target() {
        GlThreadGuard.checkGlThread("OffscreenFbo.target");
        ensureTarget();
        return target;
    }

    /** 绑定 FBO 为写目标（渲染线程）。 */
    public void bind() {
        target().bindWrite(true);
    }

    /** 解绑 FBO（渲染线程）。 */
    public void unbind() {
        if (target != null) {
            target.unbindWrite();
        }
    }

    /** @return 颜色纹理 id（T2.4 PBO 回读用；无 FBO 时抛 IllegalStateException） */
    public int colorTextureId() {
        return target().getColorTextureId();
    }

    /** @return 深度纹理 id（调试/联动用） */
    public int depthTextureId() {
        return target().getDepthTextureId();
    }

    /** @return 当前 FBO 宽（未创建时为 -1） */
    public int width() {
        return target == null ? -1 : target.width;
    }

    /** @return 当前 FBO 高（未创建时为 -1） */
    public int height() {
        return target == null ? -1 : target.height;
    }

    /** @return 是否已创建 */
    public boolean isCreated() {
        return target != null;
    }

    /** @return 累计重建次数（分辨率变更；验收：连续 1000 帧不应增长） */
    public long rebuildCount() {
        return rebuildCount;
    }

    private void ensureTarget() {
        int desiredWidth = CaptureParams.get().videoWidth();
        int desiredHeight = CaptureParams.get().videoHeight();
        if (target != null && width == desiredWidth && height == desiredHeight) {
            return;
        }
        if (target != null) {
            target.destroyBuffers();
            rebuildCount++;
            LOGGER.info("{} 离屏 FBO 重建：{}x{} -> {}x{}（配置变更）", CaptureConstants.LOG_MARKER,
                    width, height, desiredWidth, desiredHeight);
        }
        this.target = new TextureTarget(desiredWidth, desiredHeight, true, false);
        this.width = desiredWidth;
        this.height = desiredHeight;
    }
}
