package com.captureapi.video;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.ManagedGlResource;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.video.frame.RawFrame;
import java.nio.ByteBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双 PBO 异步回读资源（I2.4.1 / I2.4.2 / I2.4.3）。
 *
 * <p>写第 N 帧、读第 N-1 帧：渲染完成后把离屏 FBO 像素异步排入 PBO_A，同时映射 PBO_B 拷贝上一帧数据，
 * 然后交换角色。首帧只写不读（自然丢首帧）。映射失败/尺寸重建帧返回 null（丢帧不阻塞）。</p>
 *
 * <p>数据流：PBO 映射区 → 渲染线程立即拷贝到堆内 {@code byte[]} → 立即 unmap → {@link RawFrame}；
 * 堆数据可安全交给编码线程。行序为 GL 自下而上，由编码阶段翻转。</p>
 *
 * <p>生命周期：经 WorldResourceManager 注册（键 {@link #KEY}），进世界创建、退世界销毁、全程复用；
 * 尺寸随 video_resolution 惰性重建（与 FBO 同一配置源）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PboPairResource implements ManagedGlResource {

    /** 资源键（T2.4.1 固定键）。 */
    public static final String KEY = CaptureConstants.RESOURCE_KEY_PREFIX + "video:pbo_pair";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private final int[] buffers = new int[CaptureConstants.PBO_PAIR_COUNT];
    private int width = -1;
    private int height = -1;
    private int capacity;
    private int writeIndex;
    private boolean hasPrevious;
    private long readFrameCount;
    private long droppedFrameCount;
    private long rebuildCount;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public void create() {
        ensureCapacity();
        LOGGER.info("{} 双 PBO 创建：{}x{}，容量={} bytes，pbo=[{}, {}]",
                CaptureConstants.LOG_MARKER, width, height, capacity, buffers[0], buffers[1]);
    }

    @Override
    public void destroy() {
        if (buffers[0] != 0 || buffers[1] != 0) {
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] != 0) {
                    GL15.glDeleteBuffers(buffers[i]);
                    buffers[i] = 0;
                }
            }
            LOGGER.info("{} 双 PBO 销毁：{}x{}", CaptureConstants.LOG_MARKER, width, height);
        }
        width = -1;
        height = -1;
        capacity = 0;
        writeIndex = 0;
        hasPrevious = false;
    }

    /**
     * 回读上一帧（渲染线程；调用时离屏 FBO 必须仍为当前读/写 framebuffer）。
     *
     * @return 上一帧 RawFrame；首帧/尺寸重建帧/映射失败返回 null
     */
    public RawFrame readback() {
        GlThreadGuard.checkGlThread("PboPair.readback");
        boolean canRead = ensureCapacity();
        long timestamp = CaptureClock.get().nowNanos();

        int readIndex = 1 - writeIndex;
        int writeIndexCurrent = writeIndex;

        // 写当前帧（异步排队）
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[writeIndexCurrent]);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);

        RawFrame frame = null;
        if (canRead && hasPrevious) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[readIndex]);
            ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0L, capacity,
                    GL30.GL_MAP_READ_BIT);
            if (mapped != null) {
                try {
                    byte[] rgba = new byte[capacity];
                    mapped.get(rgba);
                    frame = new RawFrame(rgba, width, height, timestamp);
                } finally {
                    GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                }
                readFrameCount++;
            } else {
                droppedFrameCount++;
                if (droppedFrameCount % 60L == 1L) {
                    LOGGER.warn("{} PBO 映射失败，丢弃该帧（不阻塞）：累计丢帧={}", CaptureConstants.LOG_MARKER,
                            droppedFrameCount);
                }
            }
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        writeIndex = readIndex;
        hasPrevious = true;
        return frame;
    }

    /** @return 累计成功回读帧数 */
    public long readFrameCount() {
        return readFrameCount;
    }

    /** @return 累计丢帧数（首帧不计入） */
    public long droppedFrameCount() {
        return droppedFrameCount;
    }

    /** @return 累计重建次数（分辨率变更） */
    public long rebuildCount() {
        return rebuildCount;
    }

    /** @return 当前 PBO 宽（未创建 -1） */
    public int width() {
        return width;
    }

    /** @return 当前 PBO 高（未创建 -1） */
    public int height() {
        return height;
    }

    /** @return 是否已创建 */
    public boolean isCreated() {
        return buffers[0] != 0 && buffers[1] != 0;
    }

    /**
     * 确保容量与当前配置分辨率一致；尺寸变更时重建两个 PBO。
     *
     * @return true=容量就绪且本帧可读；false=刚重建（本帧应丢）
     */
    private boolean ensureCapacity() {
        int desiredWidth = CaptureParams.get().videoWidth();
        int desiredHeight = CaptureParams.get().videoHeight();
        int desiredCapacity = desiredWidth * desiredHeight * CaptureConstants.RGBA_BYTES_PER_PIXEL;
        if (isCreated() && width == desiredWidth && height == desiredHeight && capacity == desiredCapacity) {
            return true;
        }
        if (isCreated()) {
            for (int i = 0; i < buffers.length; i++) {
                GL15.glDeleteBuffers(buffers[i]);
                buffers[i] = 0;
            }
            rebuildCount++;
            LOGGER.info("{} 双 PBO 重建：{}x{} -> {}x{}（配置变更）", CaptureConstants.LOG_MARKER,
                    width, height, desiredWidth, desiredHeight);
        }
        width = desiredWidth;
        height = desiredHeight;
        capacity = desiredCapacity;
        for (int i = 0; i < buffers.length; i++) {
            buffers[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffers[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, capacity, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        writeIndex = 0;
        hasPrevious = false;
        return false;
    }
}
