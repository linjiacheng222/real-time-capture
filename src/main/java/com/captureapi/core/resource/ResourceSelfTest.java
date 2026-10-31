package com.captureapi.core.resource;

import com.captureapi.core.constant.CaptureConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界资源管理器自检（I1.2.1/I1.2.3，仅开发环境）。
 *
 * <p>在渲染线程上使用一个<b>独立的</b>管理器实例模拟：注册 → 进入世界创建 → 取用 →
 * 退出世界销毁 → 重复退出幂等 → 销毁后访问抛异常。不触碰全局单例，不影响游戏世界状态。</p>
 *
 * <p>由 {@code com.captureapi.core.CoreSelfTest} 在 {@code captureapi.debug.selftest=true}
 * 时调度执行；结果输出 `[CaptureAPI] 核心自检 ...` 日志，作为 M1 验收证据。</p>
 */
public final class ResourceSelfTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private static final String PROBE_KEY = NoopProbeResource.KEY;
    private static final String PROBE2_KEY = CaptureConstants.RESOURCE_KEY_PREFIX + "selftest:probe2";

    private ResourceSelfTest() {
        throw new AssertionError("no instances");
    }

    /**
     * 执行资源管理器自检（必须在渲染线程调用；内部会触发 GL 线程守卫断言）。
     *
     * @return 全部检查通过返回 true；否则 false 并输出失败原因
     */
    public static boolean run() {
        StringBuilder failures = new StringBuilder();
        WorldResourceManager manager = new WorldResourceManager();
        manager.setLifecycleLogging(true);

        check(!manager.isWorldReady(), "初始状态应为未就绪", failures);
        manager.registerWorldScoped(PROBE_KEY, NoopProbeResource::new);
        manager.registerWorldScoped(PROBE2_KEY, () -> new NoopProbeResource() {
            @Override
            public String key() {
                return PROBE2_KEY;
            }
        });
        check(manager.activeWorldResourceCount() == 0, "未进入世界时不应创建资源", failures);
        check(manager.registeredWorldResourceCount() == 2, "应注册 2 个世界资源工厂", failures);

        manager.onWorldJoin();
        check(manager.isWorldReady(), "onWorldJoin 后 isWorldReady 应为 true", failures);
        check(manager.activeWorldResourceCount() == 2, "onWorldJoin 后应创建 2 个世界资源", failures);
        NoopProbeResource probe = manager.get(PROBE_KEY, NoopProbeResource.class);
        check(probe.isCreated(), "资源 create() 应被调用", failures);

        manager.onWorldJoin();
        check(manager.activeWorldResourceCount() == 2, "onWorldJoin 幂等（不应重复创建）", failures);

        manager.onWorldLeave();
        check(!manager.isWorldReady(), "onWorldLeave 后 isWorldReady 应为 false", failures);
        check(manager.activeWorldResourceCount() == 0, "onWorldLeave 后存活世界资源应为 0", failures);
        check(probe.isDestroyed(), "资源 destroy() 应被调用", failures);

        manager.onWorldLeave();
        check(manager.activeWorldResourceCount() == 0, "重复 onWorldLeave 幂等（不应异常）", failures);

        boolean accessFailed;
        try {
            manager.get(PROBE_KEY, NoopProbeResource.class);
            accessFailed = false;
        } catch (IllegalStateException expected) {
            accessFailed = true;
        }
        check(accessFailed, "销毁后 get() 应抛 IllegalStateException", failures);

        WorldResourceStats stats = manager.getStats();
        check(stats.createdTotal() >= 2 && stats.destroyedTotal() >= 2, "统计计数应记录创建/销毁", failures);
        check(stats.failedCreates() == 0 && stats.failedDestroys() == 0, "自检不应出现失败", failures);

        if (failures.length() == 0) {
            LOGGER.info("{} 资源管理器自检 PASS：{}", CaptureConstants.LOG_MARKER, stats);
            return true;
        }
        LOGGER.error("{} 资源管理器自检 FAIL：{}；stats={}", CaptureConstants.LOG_MARKER, failures, stats);
        return false;
    }

    private static void check(boolean condition, String message, StringBuilder failures) {
        if (!condition) {
            if (failures.length() > 0) {
                failures.append("；");
            }
            failures.append(message);
        }
    }
}
