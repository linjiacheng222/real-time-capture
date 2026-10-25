package com.captureapi;

import net.minecraftforge.fml.common.Mod;

/**
 * 捕获接口（Capture Interface）v0.5 —— 模组主类。
 *
 * <p>本类仅为生命周期挂接入口（骨架文件，随附录E落地创建）；
 * 具体初始化流程按 {@code I1.1.2 模组主类与生命周期挂载} 细化实现，
 * 底座设施（资源管理/时钟/参数门面/线程守卫）见 {@code com.captureapi.core}。</p>
 *
 * <p>红线遵守：Mojmap 映射、禁 Connector、GL 仅渲染主线程、
 * 唯一时间基准 System.nanoTime()、参数禁硬编码、MIT 协议。</p>
 */
@Mod(CaptureInterface.MODID)
public final class CaptureInterface {

    /** 模组唯一标识（与 gradle.properties#modId 一致）。 */
    public static final String MODID = "captureapi";

    public CaptureInterface() {
        // T1.1.2 细化后挂接客户端生命周期事件（LoggingIn/LoggingOut 等）
    }
}
