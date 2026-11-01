package com.captureapi.video.encode;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.frame.RawFrame;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPEG 编码器（I2.5.1）。
 *
 * <p>RGBA8（GL 行序自下而上）→ RGB（丢弃 alpha）→ 逐行倒序写入 {@link BufferedImage} →
 * ImageIO JPEG 高质量参数编码为内存 byte[]。质量取 {@code jpeg_quality/100}。
 * 编码器按线程复用 ImageWriter（ThreadLocal），支持渲染线程内联与异步编码线程。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class JpegEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    /** 输出缓冲初始容量（避免小帧反复扩容）。 */
    private static final int INITIAL_OUTPUT_BUFFER = 4096;
    /** 预热编码质量（仅触发 ImageIO 初始化）。 */
    private static final int PREWARM_QUALITY = 75;

    private final ThreadLocal<ImageWriter> writers = ThreadLocal.withInitial(() -> {
        java.util.Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
        if (!it.hasNext()) {
            throw new IllegalStateException("JDK 未提供 JPEG ImageWriter");
        }
        return it.next();
    });
    private final ThreadLocal<ImageWriteParam> params = ThreadLocal.withInitial(() -> writers.get().getDefaultWriteParam());
    private final ThreadLocal<EncodeState> states = ThreadLocal.withInitial(EncodeState::new);

    static {
        // 禁用 ImageIO 磁盘缓存，避免每帧 createImageOutputStream 的缓存/临时文件开销
        ImageIO.setUseCache(false);
    }

    /** 每线程复用的编码状态（图像/行缓冲/输出流），避免每帧大对象分配。 */
    private static final class EncodeState {
        private BufferedImage image;
        private int[] row;
        private ByteArrayOutputStream bytes;

        private void ensure(int width, int height) {
            if (image == null || image.getWidth() != width || image.getHeight() != height) {
                image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                row = new int[width];
            }
            if (bytes == null) {
                bytes = new ByteArrayOutputStream(Math.max(INITIAL_OUTPUT_BUFFER, width * height / 8));
            }
        }
    }

    /**
     * 编码一帧。
     *
     * @param frame    原始 RGBA 帧（自下而上行序）
     * @param quality  JPEG 质量 0~100
     * @return JPEG 字节
     * @throws IOException 编码/写出失败
     */
    public byte[] encode(RawFrame frame, int quality) throws IOException {
        int width = frame.width();
        int height = frame.height();
        byte[] rgba = frame.rgba();
        EncodeState state = states.get();
        state.ensure(width, height);
        BufferedImage image = state.image;
        int[] row = state.row;
        for (int y = 0; y < height; y++) {
            int sourceRow = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int i = sourceRow + x * 4;
                int r = rgba[i] & 0xFF;
                int g = rgba[i + 1] & 0xFF;
                int b = rgba[i + 2] & 0xFF;
                row[x] = (r << 16) | (g << 8) | b;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }

        ImageWriter writer = writers.get();
        ImageWriteParam param = params.get();
        writer.reset();
        ByteArrayOutputStream bytes = state.bytes;
        bytes.reset();
        ImageOutputStream output = new MemoryCacheImageOutputStream(bytes);
        try {
            writer.setOutput(output);
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                float q = Math.max(0.0F, Math.min(1.0F, quality / 100.0F));
                param.setCompressionQuality(q);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            output.flush();
        } finally {
            try {
                writer.setOutput(null);
            } finally {
                output.close();
            }
        }
        return bytes.toByteArray();
    }

    /** 模组 setup 期预热一次（JVM ImageIO 服务加载冷启动；失败只告警不阻断）。 */
    public static void prewarm() {
        try {
            RawFrame one = new RawFrame(new byte[] {(byte) 0, (byte) 0, (byte) 0, (byte) 0xFF}, 1, 1, 0L);
            new JpegEncoder().encode(one, PREWARM_QUALITY);
            LOGGER.debug("{} JPEG 编码器预热完成", CaptureConstants.LOG_MARKER);
        } catch (Throwable t) {
            LOGGER.warn("{} JPEG 编码器预热失败（不影响启动，首帧可能稍慢）", CaptureConstants.LOG_MARKER, t);
        }
    }
}
