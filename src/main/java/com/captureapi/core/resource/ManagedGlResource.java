package com.captureapi.core.resource;

/**
 * GL 资源标记接口（I1.2.1）。
 *
 * <p>实现本接口的资源，其 {@code create()/destroy()} 由 {@link WorldResourceManager}
 * 强制经 {@code GlThreadGuard.checkGlThread()} 校验在渲染主线程执行（红线 3）。
 * 非 GL 的全局资源（如音频设备句柄）可直接实现 {@link ManagedResource}，不受 GL 线程约束。</p>
 */
public interface ManagedGlResource extends ManagedResource {
}
