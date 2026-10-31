package com.captureapi.core.config;

/**
 * 参数快照刷新范围（I1.5.3）。
 */
public enum ReloadScope {
    /** 首次配置加载（Loading 事件）。 */
    INITIAL_LOAD,
    /** 配置文件重载（Reloading 事件）。 */
    RELOAD
}
