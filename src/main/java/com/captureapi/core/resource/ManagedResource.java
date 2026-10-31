package com.captureapi.core.resource;

/**
 * 受 {@link WorldResourceManager} 管理的资源基接口（I1.2.1）。
 *
 * <p>管理器只关心资源的生灭时机（创建/销毁），不关心资源语义；资源本体由各模块
 * （M2 的 FBO/PBO/纹理、M3 的监听器会话/音频资源）实现并在管理器登记。</p>
 *
 * <p>生命周期：注册工厂 →（世界级：进世界时）`create()` → 全程复用 → 退世界时 `destroy()`。
 * 实现类须保证 `create()`/`destroy()` 幂等安全（管理器保证不重复调用，但资源自身也应防御）。</p>
 */
public interface ManagedResource {

    /** @return 唯一资源键（建议以 {@code captureapi:} 为前缀，便于日志定位） */
    String key();

    /** 创建资源（世界级资源由管理器在世界进入时调用；全局级资源在注册时由管理器调用）。 */
    void create();

    /** 销毁资源（世界级资源在世界退出时调用；全局级资源在客户端关闭时调用）。 */
    void destroy();
}
