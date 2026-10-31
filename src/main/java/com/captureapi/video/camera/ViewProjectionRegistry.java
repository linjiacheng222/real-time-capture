package com.captureapi.video.camera;

import com.captureapi.core.constant.CaptureConstants;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 投影形状注册表（I2.1.4）。
 *
 * <p>字符串 id → {@link ViewProjection} 实现；M2 注册 {@code "rect"}，T4.8 可注册球面锥形状。
 * 未注册 id 快速失败（带 id 的明确异常）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class ViewProjectionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final Map<String, ViewProjection> REGISTRY = new ConcurrentHashMap<>();

    static {
        register(new RectProjection());
        register(new SphereConeProjection());
    }

    private ViewProjectionRegistry() {
        throw new AssertionError("no instances");
    }

    /**
     * 注册形状（重复 id 覆盖并告警）。
     *
     * @param projection 形状实现
     */
    public static void register(ViewProjection projection) {
        Objects.requireNonNull(projection, "projection");
        ViewProjection previous = REGISTRY.put(projection.id(), projection);
        if (previous != null && previous != projection) {
            LOGGER.warn("{} 投影形状 id 重复注册并覆盖：{}", CaptureConstants.LOG_MARKER, projection.id());
        }
    }

    /**
     * @param id 形状 id
     * @return 形状实现
     * @throws IllegalArgumentException 未注册
     */
    public static ViewProjection get(String id) {
        ViewProjection projection = REGISTRY.get(id);
        if (projection == null) {
            throw new IllegalArgumentException("未注册的投影形状 id：" + id);
        }
        return projection;
    }

    /** @return 默认矩形投影响应 id */
    public static String defaultId() {
        return RectProjection.ID;
    }
}
