package com.captureapi.video.filter;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.frame.RawFrame;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 滤镜注册表与发现机制（T8.1 / I8.1.1/I8.1.5/I8.1.6）。
 *
 * <p>扫描 `filter_directory`（默认 config/captureapi/filters），一文件一滤镜；JSON 参数式
 * 解析并校验（失败跳过 + WARN 四要素），内置 grayscale 滤镜随 jar 提供。选中滤镜在编码前
 * 应用（CPU 引擎）；单滤镜异常即禁用并回退原帧，捕获主链不中断。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class FilterRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final FilterRegistry INSTANCE = new FilterRegistry();
    private static final String README = """
            CaptureAPI 滤镜目录（T8.1）
            一个 JSON 文件 = 一个滤镜。格式：
            {
              "name": "my_filter",
              "version": 1,
              "ops": [
                {"type": "brightness", "params": {"value": 0.1}},
                {"type": "grayscale"}
              ]
            }
            支持的 op：brightness[-1,1]、contrast[-1,1]、saturation[0,2]、
            hue(degrees)[-180,180]、grayscale、invert。按数组顺序执行。
            GLSL-as-JSON 当前未启用（I8.1.4 可行性门未通过），带 glsl 字段的文件将被跳过。
            """;

    private final AtomicLong appliedFrames = new AtomicLong();
    private final AtomicLong failedFilters = new AtomicLong();
    private volatile Map<String, FilterDefinition> filters = Map.of();
    private volatile String selectedName;
    private volatile Path directory;
    private volatile String lastError;

    private FilterRegistry() {
    }

    public static FilterRegistry get() {
        return INSTANCE;
    }

    /** 重新扫描滤镜目录（客户端 setup/热重载/指令调用）；原子替换清单。 */
    public synchronized void reload() {
        Path dir = FMLPaths.GAMEDIR.get().resolve(CaptureParams.get().filterDirectory());
        directory = dir;
        try {
            Files.createDirectories(dir);
            Path readme = dir.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, README, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            lastError = "滤镜目录创建失败：" + e.getMessage();
            LOGGER.warn("{} [FILTER] {}", CaptureConstants.LOG_MARKER, lastError);
        }
        Map<String, FilterDefinition> loaded = new LinkedHashMap<>();
        loaded.put("grayscale", builtinGrayscale());
        int bad = 0;
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                List<Path> files = stream.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(".json")).toList();
                for (Path file : files) {
                    try {
                        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                        if (json.has("glsl")) {
                            bad++;
                            LOGGER.warn("{} [FILTER] 跳过 GLSL-as-JSON（未启用）：{}（I8.1.4）",
                                    CaptureConstants.LOG_MARKER, file.getFileName());
                            continue;
                        }
                        FilterDefinition definition = FilterDefinition.parse(json,
                                file.getFileName().toString());
                        if (loaded.putIfAbsent(definition.name(), definition) != null) {
                            LOGGER.warn("{} [FILTER] 滤镜重名（后者覆盖）：{}（文件 {}）", CaptureConstants.LOG_MARKER,
                                    definition.name(), file.getFileName());
                            loaded.put(definition.name(), definition);
                        }
                    } catch (Throwable t) {
                        bad++;
                        LOGGER.warn("{} [FILTER] 滤镜加载失败：文件={}，原因={}；修正建议：检查 JSON/ops/参数范围 "
                                        + "（如 brightness.value∈[-1,1]）",
                                CaptureConstants.LOG_MARKER, file.getFileName(), t.getMessage());
                    }
                }
            } catch (IOException e) {
                lastError = "滤镜目录扫描失败：" + e.getMessage();
                LOGGER.warn("{} [FILTER] {}", CaptureConstants.LOG_MARKER, lastError);
            }
        }
        filters = Collections.unmodifiableMap(loaded);
        if (selectedName != null && !filters.containsKey(selectedName)) {
            LOGGER.warn("{} [FILTER] 已选滤镜在新清单中不存在，已清除选中态：{}", CaptureConstants.LOG_MARKER,
                    selectedName);
            selectedName = null;
        }
        LOGGER.info("{} [FILTER] 滤镜扫描完成：目录={}，可用={}，失败/跳过={}，选中={}",
                CaptureConstants.LOG_MARKER, dir, filters.keySet(), bad, selectedName);
    }

    /** @return 全部滤镜名（含内置 grayscale） */
    public List<String> names() {
        return new ArrayList<>(filters.keySet());
    }

    /** @return 当前选中滤镜名（null=无） */
    public String selected() {
        return selectedName;
    }

    /**
     * 选择滤镜（null/空=关闭）。
     *
     * @param name 滤镜名
     * @return 是否选中成功
     */
    public synchronized boolean select(String name) {
        if (name == null || name.isBlank()) {
            selectedName = null;
            return true;
        }
        if (!filters.containsKey(name)) {
            lastError = "滤镜不存在：" + name;
            return false;
        }
        selectedName = name;
        return true;
    }

    /** @return 最近错误（诊断） */
    public String lastError() {
        return lastError;
    }

    public long appliedFrames() {
        return appliedFrames.get();
    }

    public long failedFilters() {
        return failedFilters.get();
    }

    /**
     * 对帧应用当前滤镜（编码前）。
     *
     * @param frame 原始帧
     * @return 处理后帧（无滤镜/失败时原帧）
     */
    public RawFrame applySelected(RawFrame frame) {
        String name = selectedName;
        if (name == null) {
            return frame;
        }
        FilterDefinition definition = filters.get(name);
        if (definition == null) {
            selectedName = null;
            return frame;
        }
        try {
            RawFrame filtered = FilterEngine.apply(frame, definition);
            appliedFrames.incrementAndGet();
            return filtered;
        } catch (Throwable t) {
            failedFilters.incrementAndGet();
            synchronized (this) {
                filters = new LinkedHashMap<>(filters);
                filters.remove(name);
                if (name.equals(selectedName)) {
                    selectedName = null;
                }
            }
            LOGGER.warn("{} [FILTER] 滤镜应用失败，已禁用该滤镜（捕获流继续）：{}", CaptureConstants.LOG_MARKER, name, t);
            return frame;
        }
    }

    private static FilterDefinition builtinGrayscale() {
        return new FilterDefinition("grayscale",
                List.of(new FilterDefinition.Op("grayscale", Map.of())));
    }
}
