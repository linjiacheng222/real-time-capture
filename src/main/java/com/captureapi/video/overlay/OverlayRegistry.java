package com.captureapi.video.overlay;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.frame.RawFrame;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 叠加贴图注册表与 CPU 合成（T8.2 / I8.2.1/I8.2.3）。
 *
 * <p>扫描 `overlay_directory` PNG（一文件一叠加，跳过坏文件），按 {@link OverlayModel} 列表
 * 在滤镜之后、编码之前做 CPU 2D 合成（alpha blend）；仅作用于捕获帧副本，主画面零影响。
 * 贴图资产需用户自绘（版权红线：本项目不附带参考项目资产）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class OverlayRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final OverlayRegistry INSTANCE = new OverlayRegistry();
    private static final String README = """
            CaptureAPI 叠加贴图目录（T8.2）
            放入 PNG 文件（文件名 = 叠加名，建议透明背景、自绘资产）。
            通过 WS `overlay.show` 添加图层：
            {"id":"1","action":"overlay.show","params":{"image":"watermark","x":0.5,"y":0.5,
             "scaleX":1.0,"scaleY":1.0,"rotation":0,"opacity":0.8}}
            顺序：滤镜 → 叠加 → JPEG 编码（固定，不暴露配置）。
            `overlay_enabled=false` 时叠加不生效。
            """;

    private volatile Map<String, BufferedImage> images = Map.of();
    private volatile List<OverlayModel> layers = List.of();

    private OverlayRegistry() {
    }

    public static OverlayRegistry get() {
        return INSTANCE;
    }

    /** 扫描叠加目录（客户端 setup/热重载）。 */
    public synchronized void reload() {
        Path dir = FMLPaths.GAMEDIR.get().resolve(CaptureParams.get().overlayDirectory());
        try {
            Files.createDirectories(dir);
            Path readme = dir.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, README, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.warn("{} [OVERLAY] 目录创建失败：{}", CaptureConstants.LOG_MARKER, e.getMessage());
        }
        Map<String, BufferedImage> loaded = new LinkedHashMap<>();
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(".png")).toList()) {
                    try {
                        BufferedImage image = ImageIO.read(file.toFile());
                        if (image == null) {
                            throw new IOException("不是有效 PNG");
                        }
                        String name = file.getFileName().toString();
                        name = name.substring(0, name.length() - 4);
                        loaded.put(name, image);
                    } catch (Throwable t) {
                        LOGGER.warn("{} [OVERLAY] 贴图加载失败并跳过：{}（{}）", CaptureConstants.LOG_MARKER,
                                file.getFileName(), t.getMessage());
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("{} [OVERLAY] 目录扫描失败：{}", CaptureConstants.LOG_MARKER, e.getMessage());
            }
        }
        images = Collections.unmodifiableMap(loaded);
        GpuOverlayRenderer.invalidate();
        if (!layers.isEmpty()) {
            List<OverlayModel> valid = new ArrayList<>();
            for (OverlayModel layer : layers) {
                if (images.containsKey(layer.image())) {
                    valid.add(layer);
                } else {
                    LOGGER.warn("{} [OVERLAY] 图层贴图不存在，已移除：{}", CaptureConstants.LOG_MARKER,
                            layer.image());
                }
            }
            layers = List.copyOf(valid);
        }
        LOGGER.info("{} [OVERLAY] 扫描完成：目录={}，贴图={}，图层={}", CaptureConstants.LOG_MARKER, dir,
                images.keySet(), layers.size());
    }

    /** @return 已加载贴图名 */
    public List<String> images() {
        return new ArrayList<>(images.keySet());
    }

    /** @return 指定贴图；不存在时 null（GPU 叠加读取） */
    public BufferedImage image(String name) {
        return images.get(name);
    }

    /** @return 当前图层列表（按层序） */
    public List<OverlayModel> layers() {
        return layers;
    }

    /**
     * 添加图层（层序=调用顺序）。
     *
     * @param model 叠加参数
     * @return 贴图是否存在
     */
    public synchronized boolean show(OverlayModel model) {
        if (!images.containsKey(model.image())) {
            return false;
        }
        List<OverlayModel> next = new ArrayList<>(layers);
        next.add(model);
        layers = List.copyOf(next);
        return true;
    }

    /** 清空图层。 */
    public synchronized void clear() {
        layers = List.of();
    }

    /** @return 当前是否启用且有图层 */
    public boolean active() {
        return CaptureParams.get().overlayEnabled() && !layers.isEmpty() && !images.isEmpty();
    }

    /**
     * 对帧应用叠加合成（滤镜之后、编码之前）。
     *
     * @param frame 滤镜后的帧
     * @return 合成后的帧（未启用/无图层时原帧）
     */
    public RawFrame apply(RawFrame frame) {
        if (GpuOverlayRenderer.isEnabled() || !active()) {
            return frame;
        }
        try {
            int width = frame.width();
            int height = frame.height();
            BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            byte[] rgba = frame.rgba();
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                int sourceRow = (height - 1 - y) * width * 4;
                for (int x = 0; x < width; x++) {
                    int i = sourceRow + x * 4;
                    row[x] = 0xFF000000 | ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8)
                            | (rgba[i + 2] & 0xFF);
                }
                canvas.setRGB(0, y, width, 1, row, 0, width);
            }
            Graphics2D graphics = canvas.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                for (OverlayModel layer : layers) {
                    BufferedImage image = images.get(layer.image());
                    if (image == null) {
                        continue;
                    }
                    double centerX = width * layer.x();
                    double centerY = height * layer.y();
                    AffineTransform transform = new AffineTransform();
                    transform.translate(centerX, centerY);
                    transform.rotate(Math.toRadians(layer.rotation()));
                    transform.scale(layer.scaleX(), layer.scaleY());
                    transform.translate(-image.getWidth() / 2.0D, -image.getHeight() / 2.0D);
                    graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                            (float) layer.opacity()));
                    graphics.drawImage(image, transform, null);
                }
            } finally {
                graphics.dispose();
            }
            byte[] out = new byte[rgba.length];
            int[] rowOut = new int[width];
            for (int y = 0; y < height; y++) {
                canvas.getRGB(0, y, width, 1, rowOut, 0, width);
                int targetRow = (height - 1 - y) * width * 4;
                for (int x = 0; x < width; x++) {
                    int argb = rowOut[x];
                    int i = targetRow + x * 4;
                    out[i] = (byte) ((argb >> 16) & 0xFF);
                    out[i + 1] = (byte) ((argb >> 8) & 0xFF);
                    out[i + 2] = (byte) (argb & 0xFF);
                    out[i + 3] = (byte) 0xFF;
                }
            }
            return new RawFrame(out, width, height, frame.timestampNanos());
        } catch (Throwable t) {
            LOGGER.warn("{} [OVERLAY] 合成失败，回退原帧（捕获流继续）", CaptureConstants.LOG_MARKER, t);
            return frame;
        }
    }
}
