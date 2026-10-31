package com.captureapi.video.camera;

import com.captureapi.core.constant.CaptureConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 相机全生命周期管理 API（I2.1.1 / I2.1.3）。
 *
 * <p>线程安全：内部 {@link ConcurrentHashMap} + volatile active id；所有写操作来自 M4 网络线程或主线程，
 * 整体替换不可变 {@link CameraSpec}。渲染线程应在帧首调用 {@link #active()} 一次并整帧使用同一快照。</p>
 *
 * <p>单路激活：任意时刻最多一个 active 相机产生渲染；创建首个相机自动激活；删除 active 后 active=0
 * （停止渲染，帧缓存语义由 M4/T4.2 定义）。id 为 int 自增（协议 /camera/{id}/ 友好）。</p>
 */
public final class CameraManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final CameraManager INSTANCE = new CameraManager();

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CameraSpec> cameras = new ConcurrentHashMap<>();
    private volatile int activeCameraId;

    private CameraManager() {
    }

    public static CameraManager get() {
        return INSTANCE;
    }

    /**
     * 创建相机；第一个创建的相机自动成为 active。
     *
     * @param params 创建参数（不可变/DEFENSIVE）
     * @return 创建的相机快照
     */
    public CameraSpec create(CameraCreateParams params) {
        Objects.requireNonNull(params, "params");
        int id = nextId.getAndIncrement();
        int[] resolution = resolveResolution(params.width(), params.height());
        CameraSpec spec = new CameraSpec(id, params.x(), params.y(), params.z(), params.yawDegrees(),
                params.pitchDegrees(), params.rollDegrees(), params.fovDegrees(), resolution[0], resolution[1],
                params.boundEntityUuid(), params.offsetX(), params.offsetY(), params.offsetZ(),
                params.projectionId(), false);
        cameras.put(id, spec);
        if (activeCameraId == 0) {
            activeCameraId = id;
        }
        LOGGER.info("{} 相机创建：id={}，active={}，pos=({}, {}, {})，rot=({}, {}, {})，fov={}，{}x{}，bound={}",
                CaptureConstants.LOG_MARKER, id, activeCameraId, params.x(), params.y(), params.z(),
                params.yawDegrees(), params.pitchDegrees(), params.rollDegrees(), params.fovDegrees(),
                resolution[0], resolution[1], params.boundEntityUuid());
        return spec;
    }

    /**
     * 整体替换相机参数（保留 id 与暂停状态）。
     *
     * @param id     相机 id
     * @param params 新参数
     * @return 新快照
     */
    public CameraSpec adjust(int id, CameraCreateParams params) {
        Objects.requireNonNull(params, "params");
        CameraSpec old = require(id);
        int[] resolution = resolveResolution(params.width(), params.height());
        CameraSpec updated = old.withParams(params, resolution[0], resolution[1]);
        cameras.put(id, updated);
        LOGGER.info("{} 相机调整：id={}，pos=({}, {}, {})，rot=({}, {}, {})，fov={}，{}x{}，bound={}",
                CaptureConstants.LOG_MARKER, id, params.x(), params.y(), params.z(), params.yawDegrees(),
                params.pitchDegrees(), params.rollDegrees(), params.fovDegrees(), resolution[0], resolution[1],
                params.boundEntityUuid());
        return updated;
    }

    /** 暂停相机（停止离屏渲染；保留相机与最后一帧缓存）。 */
    public CameraSpec pause(int id) {
        CameraSpec paused = require(id).withPaused(true);
        cameras.put(id, paused);
        LOGGER.info("{} 相机暂停：id={}", CaptureConstants.LOG_MARKER, id);
        return paused;
    }

    /** 恢复相机。 */
    public CameraSpec resume(int id) {
        CameraSpec resumed = require(id).withPaused(false);
        cameras.put(id, resumed);
        LOGGER.info("{} 相机恢复：id={}", CaptureConstants.LOG_MARKER, id);
        return resumed;
    }

    /** 删除相机（释放关联状态；若删除 active 则 active=0）。 */
    public void delete(int id) {
        CameraSpec removed = cameras.remove(id);
        if (removed == null) {
            return;
        }
        if (activeCameraId == id) {
            activeCameraId = 0;
        }
        LOGGER.info("{} 相机删除：id={}，active={}", CaptureConstants.LOG_MARKER, id, activeCameraId);
    }

    /** 切换 active 渲染相机（单路约束）。 */
    public CameraSpec activate(int id) {
        require(id);
        activeCameraId = id;
        CameraSpec spec = require(id);
        LOGGER.info("{} 相机激活：id={}", CaptureConstants.LOG_MARKER, id);
        return spec;
    }

    /** @return 当前 active 相机快照；无相机或 active=0 时返回 null（渲染线程帧首调用一次） */
    public CameraSpec active() {
        int id = activeCameraId;
        return id == 0 ? null : cameras.get(id);
    }

    /** @return 当前 active id；0 表示无激活 */
    public int activeCameraId() {
        return activeCameraId;
    }

    /** @return 指定 id 的相机快照；不存在返回 null */
    public CameraSpec get(int id) {
        return cameras.get(id);
    }

    /** @return 全部相机快照（按 id 升序，不可变列表） */
    public List<CameraSpec> list() {
        ArrayList<CameraSpec> copy = new ArrayList<>(cameras.values());
        copy.sort(Comparator.comparingInt(CameraSpec::id));
        return List.copyOf(copy);
    }

    /** @return 相机数量 */
    public int size() {
        return cameras.size();
    }

    /** 清空全部相机（世界卸载/测试用；active 归零）。 */
    public void clear() {
        cameras.clear();
        activeCameraId = 0;
        LOGGER.info("{} 相机全部清空", CaptureConstants.LOG_MARKER);
    }

    private CameraSpec require(int id) {
        CameraSpec spec = cameras.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("相机不存在：id=" + id);
        }
        return spec;
    }

    private static int[] resolveResolution(int width, int height) {
        if (width > 0 && height > 0) {
            return new int[] {width, height};
        }
        String configured = com.captureapi.core.config.CaptureParams.get().videoResolution();
        String[] parts = configured == null ? new String[0] : configured.split("x");
        try {
            int w = Integer.parseInt(parts[0]);
            int h = Integer.parseInt(parts[1]);
            if (w > 0 && h > 0) {
                return new int[] {w, h};
            }
        } catch (RuntimeException ignored) {
            // 配置在框架层有正则约束；异常仅作兜底
        }
        return new int[] {1280, 1024};
    }

    /** 便于调试：绑定实体是否为该相机（不解析世界）。 */
    public static boolean isBound(CameraSpec spec) {
        UUID uuid = spec.boundEntityUuid();
        return uuid != null;
    }

    /** 便于调试：快照位置字符串。 */
    public static String describe(CameraSpec spec) {
        Vector3d pos = spec.position();
        return "#" + spec.id() + "@(" + pos.x + "," + pos.y + "," + pos.z + ")";
    }
}
