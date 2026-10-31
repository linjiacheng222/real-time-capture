package com.captureapi.core.resource;

import com.captureapi.core.CaptureConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 空壳世界资源示例（I1.2.1）。
 *
 * <p>不含任何 GL 调用，仅记录 create/destroy 轨迹，用于 M1 验收与 M2/M3 资源模板参考。
 * 仅在开发自检（{@code captureapi.debug.selftest=true}）时由 {@link ResourceSelfTest} 注册，
 * 不影响正常游戏会话。</p>
 */
public class NoopProbeResource implements ManagedGlResource {

    /** 资源键（开发自检专用）。 */
    public static final String KEY = CaptureConstants.RESOURCE_KEY_PREFIX + "selftest:probe";

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private volatile boolean created;
    private volatile boolean destroyed;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public void create() {
        created = true;
        LOGGER.debug("{} 资源创建：{}（NoopProbeResource 模板）", CaptureConstants.LOG_MARKER, KEY);
    }

    @Override
    public void destroy() {
        destroyed = true;
        LOGGER.debug("{} 资源销毁：{}（NoopProbeResource 模板）", CaptureConstants.LOG_MARKER, KEY);
    }

    /** @return 是否已创建（自检用） */
    public boolean isCreated() {
        return created;
    }

    /** @return 是否已销毁（自检用） */
    public boolean isDestroyed() {
        return destroyed;
    }
}
