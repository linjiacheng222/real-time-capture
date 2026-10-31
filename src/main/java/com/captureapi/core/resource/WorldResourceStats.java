package com.captureapi.core.resource;

/**
 * 资源管理器统计快照（I1.2.3）。
 *
 * @param createdTotal        累计创建次数
 * @param destroyedTotal      累计销毁次数
 * @param failedCreates       创建失败次数（异常被隔离，不计入存活）
 * @param failedDestroys      销毁失败次数（异常被隔离，仍从存活移除）
 * @param aliveWorldResources 当前存活的世界级资源数
 * @param aliveGlobalResources 当前存活的全局级资源数
 * @param registeredWorldResources 已注册的世界级资源工厂数
 * @param worldReady          当前是否处于"世界已就绪"状态
 */
public record WorldResourceStats(
        long createdTotal,
        long destroyedTotal,
        long failedCreates,
        long failedDestroys,
        int aliveWorldResources,
        int aliveGlobalResources,
        int registeredWorldResources,
        boolean worldReady) {

    @Override
    public String toString() {
        return "WorldResourceStats{created=" + createdTotal
                + ", destroyed=" + destroyedTotal
                + ", failedCreate=" + failedCreates
                + ", failedDestroy=" + failedDestroys
                + ", aliveWorld=" + aliveWorldResources
                + ", aliveGlobal=" + aliveGlobalResources
                + ", registeredWorld=" + registeredWorldResources
                + ", worldReady=" + worldReady + "}";
    }
}
