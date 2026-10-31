package com.captureapi;

import com.captureapi.core.CaptureConstants;
import com.captureapi.core.resource.WorldResourceManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 捕获接口（Capture Interface）v0.5 —— 模组主类（I1.1.2）。
 *
 * <p>职责：模组生命周期入口与后续模块初始化顺序的装配点。T1.1~T1.3 之后：</p>
 * <ul>
 *   <li>取模组事件总线（供 T1.4/T1.5 服务装配）；</li>
 *   <li>注册客户端关闭钩子（{@link WorldResourceManager#shutdown()}，回收全局级资源）；</li>
 *   <li>客户端 setup 基线日志与 M1 自检由 {@link com.captureapi.core.ClientSetupHandler} 负责。</li>
 * </ul>
 *
 * <p>红线遵守：Mojmap 映射、禁 Connector、GL 仅渲染主线程、唯一时间基准
 * {@code CaptureClock}、参数禁硬编码、MIT 协议。</p>
 */
@Mod(CaptureInterface.MODID)
public final class CaptureInterface {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    /** 模组唯一标识（与 gradle.properties#modId 一致）。 */
    public static final String MODID = "captureapi";

    /**
     * 模组构造器（Forge 47.4.x 支持对 {@link FMLJavaModLoadingContext} 的构造器注入；
     * 避免已标记「待删除」的静态 {@code FMLJavaModLoadingContext.get()} 弃用告警）。
     *
     * @param context 本模组的加载上下文（由 Forge 注入）
     */
    public CaptureInterface(FMLJavaModLoadingContext context) {
        // 模组事件总线：T1.4（CaptureClock）/T1.5（CaptureParams）等服务的装配点。
        // 具体注册在各自任务实现时接入，保持单一初始化入口，避免多入口漂移。
        context.getModEventBus();

        // 客户端关闭钩子：回收全局级资源（世界级资源已在退世界事件中回收）。
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> WorldResourceManager.get().shutdown(), "captureapi-shutdown"));

        LOGGER.info("{} 主类构造完成（modid={}，客户端事件订阅由 @Mod.EventBusSubscriber 注册）",
                CaptureConstants.LOG_MARKER, MODID);
    }
}
