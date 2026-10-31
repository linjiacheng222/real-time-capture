package com.captureapi.core.resource;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.thread.GlThreadGuard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界生命周期资源管理器（I1.2.1/I1.2.3）。
 *
 * <p>红线 3 的制度保障：FBO/PBO/纹理等世界级 GL 资源"进世界创建、全程复用、退世界销毁"；
 * 全局级资源（如音频设备）随模组生命周期创建/销毁。管理器只负责生灭时机，资源语义由各模块定义。</p>
 *
 * <p><b>线程规约</b>：{@link ManagedGlResource} 的 {@code create()/destroy()} 必须由本管理器在
 * 渲染主线程调用（内部经 {@link GlThreadGuard#checkGlThread(String)} 校验）；调用方从非渲染线程
 * 触发时，应经 {@link GlThreadGuard#runOnRenderThread(Runnable)} 投递。</p>
 *
 * <p><b>幂等性</b>：{@link #onWorldLeave()} 可重复调用；无世界资源时为空操作。
 * 资源创建/销毁异常被隔离（记录日志并计数），不阻断其它资源与游戏主流程。</p>
 */
public final class WorldResourceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private static final WorldResourceManager INSTANCE = new WorldResourceManager();

    private final Object lifecycleLock = new Object();
    private final Map<String, Supplier<? extends ManagedResource>> worldFactories = new LinkedHashMap<>();
    private final List<String> worldCreationOrder = new ArrayList<>();
    private final Map<String, ManagedResource> worldResources = new ConcurrentHashMap<>();
    private final Map<String, ManagedResource> globalResources = new ConcurrentHashMap<>();

    private final AtomicLong createdTotal = new AtomicLong();
    private final AtomicLong destroyedTotal = new AtomicLong();
    private final AtomicLong failedCreates = new AtomicLong();
    private final AtomicLong failedDestroys = new AtomicLong();

    private volatile boolean worldReady;
    private volatile boolean lifecycleLogging;

    /** 单例与自检实例共用构造（包内可见）。 */
    WorldResourceManager() {
    }

    /** @return 全局单例 */
    public static WorldResourceManager get() {
        return INSTANCE;
    }

    /**
     * 注册世界级资源工厂（惰性创建：世界进入时才实例化并 create）。
     *
     * <p>若注册时世界已就绪，则立即创建（用于世界内动态注册的资源）。</p>
     *
     * @param key     唯一资源键（建议以 {@code captureapi:} 为前缀）
     * @param factory 资源工厂
     */
    public void registerWorldScoped(String key, Supplier<? extends ManagedResource> factory) {
        Objects.requireNonNull(key, "资源键不可为 null");
        Objects.requireNonNull(factory, "资源工厂不可为 null");
        synchronized (lifecycleLock) {
            if (worldFactories.containsKey(key) || worldResources.containsKey(key)) {
                throw new IllegalArgumentException("世界资源键重复注册：" + key);
            }
            worldFactories.put(key, factory);
            logLifecycle("注册世界资源工厂：" + key);
            if (worldReady) {
                createWorldResource(key);
            }
        }
    }

    /**
     * 注册全局级资源（注册即创建；随客户端关闭销毁）。
     *
     * @param key     唯一资源键
     * @param factory 资源工厂
     */
    public void registerGlobalScoped(String key, Supplier<? extends ManagedResource> factory) {
        Objects.requireNonNull(key, "资源键不可为 null");
        Objects.requireNonNull(factory, "资源工厂不可为 null");
        synchronized (lifecycleLock) {
            if (globalResources.containsKey(key) || worldFactories.containsKey(key)) {
                throw new IllegalArgumentException("资源键已被占用：" + key);
            }
            ManagedResource resource = Objects.requireNonNull(factory.get(), "全局资源工厂返回 null：" + key);
            verifyKey(key, resource);
            createResource(resource, true);
            globalResources.put(key, resource);
        }
    }

    /**
     * 世界进入事件：统一实例化并创建全部世界级资源（幂等）。
     */
    public void onWorldJoin() {
        synchronized (lifecycleLock) {
            if (worldReady) {
                return;
            }
            worldReady = true;
            logLifecycle("世界进入：开始创建世界资源（注册数=" + worldFactories.size() + "）");
            for (String key : new ArrayList<>(worldFactories.keySet())) {
                createWorldResource(key);
            }
            WorldResourceStats stats = getStats();
            LOGGER.info("{} 世界资源就绪：{}", CaptureConstants.LOG_MARKER, stats);
        }
    }

    /**
     * 世界退出事件：逆序销毁全部世界级资源并清空（幂等）。
     */
    public void onWorldLeave() {
        synchronized (lifecycleLock) {
            worldReady = false;
            if (worldResources.isEmpty()) {
                logLifecycle("世界退出：无存活世界资源（幂等空操作）");
                return;
            }
            logLifecycle("世界退出：开始销毁世界资源（存活数=" + worldResources.size() + "）");
            for (int i = worldCreationOrder.size() - 1; i >= 0; i--) {
                String key = worldCreationOrder.get(i);
                ManagedResource resource = worldResources.remove(key);
                if (resource != null) {
                    destroyResource(resource, true);
                }
            }
            worldCreationOrder.clear();
            LOGGER.info("{} 世界资源已回收：{}", CaptureConstants.LOG_MARKER, getStats());
        }
    }

    /** 客户端关闭：安全回收资源（进程退出时不在渲染线程则跳过 GL 资源销毁，交给驱动/JVM 回收）。 */
    public void shutdown() {
        boolean onRenderThread = GlThreadGuard.isOnRenderThread();
        if (onRenderThread) {
            onWorldLeave();
        } else {
            synchronized (lifecycleLock) {
                worldReady = false;
                if (!worldResources.isEmpty()) {
                    LOGGER.warn("{} 客户端关闭发生在非渲染线程，跳过 {} 个世界级 GL 资源销毁（进程退出）",
                            CaptureConstants.LOG_MARKER, worldResources.size());
                }
                worldResources.clear();
                worldCreationOrder.clear();
            }
        }
        synchronized (lifecycleLock) {
            for (ManagedResource resource : new ArrayList<>(globalResources.values())) {
                if (!onRenderThread && resource instanceof ManagedGlResource) {
                    LOGGER.warn("{} 客户端关闭发生在非渲染线程，跳过全局 GL 资源销毁：{}",
                            CaptureConstants.LOG_MARKER, resource.key());
                    continue;
                }
                destroyResource(resource, false);
            }
            globalResources.clear();
            logLifecycle("客户端关闭：全局资源已回收");
        }
    }

    /**
     * 按资源键获取已创建的资源。
     *
     * @param key  资源键
     * @param type 期望类型
     * @param <T>  资源类型
     * @return 资源实例
     * @throws IllegalStateException    资源未创建/已销毁
     * @throws IllegalArgumentException 键不存在
     */
    public <T extends ManagedResource> T get(String key, Class<T> type) {
        Objects.requireNonNull(key, "资源键不可为 null");
        ManagedResource resource = worldResources.get(key);
        if (resource == null) {
            resource = globalResources.get(key);
        }
        if (resource == null) {
            if (worldFactories.containsKey(key)) {
                throw new IllegalStateException("资源尚未创建或已销毁：key=" + key + "（worldReady=" + worldReady + "）");
            }
            throw new IllegalArgumentException("未注册的资源键：" + key);
        }
        return type.cast(resource);
    }

    /** @return 世界是否已就绪（onWorldJoin 后、onWorldLeave 前为 true） */
    public boolean isWorldReady() {
        return worldReady;
    }

    /** @return 当前存活的世界级资源数 */
    public int activeWorldResourceCount() {
        return worldResources.size();
    }

    /** @return 已注册的世界级资源工厂数 */
    public int registeredWorldResourceCount() {
        return worldFactories.size();
    }

    /** @return 当前存活的全局级资源数 */
    public int activeGlobalResourceCount() {
        return globalResources.size();
    }

    /** @return 统计快照 */
    public WorldResourceStats getStats() {
        return new WorldResourceStats(
                createdTotal.get(),
                destroyedTotal.get(),
                failedCreates.get(),
                failedDestroys.get(),
                worldResources.size(),
                globalResources.size(),
                worldFactories.size(),
                worldReady);
    }

    /** 接入配置项 log_resource_lifecycle（T1.5 调用；默认 false）。 */
    public void setLifecycleLogging(boolean enabled) {
        this.lifecycleLogging = enabled;
    }

    /** @return 当前是否开启资源生灭日志 */
    public boolean isLifecycleLogging() {
        return lifecycleLogging;
    }

    // ================= 内部实现 =================

    private void createWorldResource(String key) {
        Supplier<? extends ManagedResource> factory = worldFactories.get(key);
        if (factory == null || !worldReady) {
            return;
        }
        ManagedResource resource = Objects.requireNonNull(factory.get(), "世界资源工厂返回 null：" + key);
        verifyKey(key, resource);
        createResource(resource, true);
        worldResources.put(key, resource);
        worldCreationOrder.add(key);
    }

    private void createResource(ManagedResource resource, boolean isWorldLevel) {
        try {
            if (resource instanceof ManagedGlResource) {
                GlThreadGuard.checkGlThread("resource.create:" + resource.key());
            }
            resource.create();
            createdTotal.incrementAndGet();
            logLifecycle("资源创建：" + resource.key() + "（" + (isWorldLevel ? "世界级" : "全局级") + "，线程=" + Thread.currentThread().getName() + "）");
        } catch (Throwable t) {
            failedCreates.incrementAndGet();
            LOGGER.error("{} 资源创建失败（已隔离）：{}", CaptureConstants.LOG_MARKER, resource.key(), t);
        }
    }

    private void destroyResource(ManagedResource resource, boolean isWorldLevel) {
        try {
            if (resource instanceof ManagedGlResource) {
                GlThreadGuard.checkGlThread("resource.destroy:" + resource.key());
            }
            resource.destroy();
            destroyedTotal.incrementAndGet();
            logLifecycle("资源销毁：" + resource.key() + "（" + (isWorldLevel ? "世界级" : "全局级") + "，线程=" + Thread.currentThread().getName() + "）");
        } catch (Throwable t) {
            failedDestroys.incrementAndGet();
            LOGGER.error("{} 资源销毁失败（已隔离，仍从存活移除）：{}", CaptureConstants.LOG_MARKER, resource.key(), t);
        }
    }

    private static void verifyKey(String expected, ManagedResource resource) {
        if (!expected.equals(resource.key())) {
            throw new IllegalArgumentException("资源键不一致：注册键=" + expected + "，资源键=" + resource.key());
        }
    }

    private void logLifecycle(String message) {
        if (lifecycleLogging) {
            LOGGER.info("{} {}", CaptureConstants.LOG_MARKER, message);
        } else {
            LOGGER.trace("{} {}", CaptureConstants.LOG_MARKER, message);
        }
    }
}
