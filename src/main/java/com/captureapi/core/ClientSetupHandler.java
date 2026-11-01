package com.captureapi.core;

import com.captureapi.CaptureInterface;
import com.captureapi.net.RemoteCaptureClient;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.core.time.CaptureClock;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端生命周期挂载（I1.1.2 / I1.4.x / I1.5.x 装配点）。
 *
 * <p>纯客户端模组：所有事件订阅类必须显式声明 {@code value = Dist.CLIENT}，防止在
 * 物理服务端歧义加载；本类在客户端 setup 阶段完成：</p>
 * <ol>
 *   <li>注册全局级资源（CaptureClock，T1.4 与 T1.2 同构）；</li>
 *   <li>打印一次基线就绪日志（I1.1.2 验收标准）；</li>
 *   <li>按需调度 M1 核心设施自检（{@link CoreSelfTest}，仅 dev 属性开启时）。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetupHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private ClientSetupHandler() {
        throw new AssertionError("no instances");
    }

    /**
     * 客户端 setup：全局资源装配 + 基线日志 + M1 设施自检入口。
     *
     * @param event Forge 客户端 setup 事件
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        RemoteCaptureClient.init();
        // T1.4：时钟作为全局级资源（T1.2 同构），随模组生命周期。
        WorldResourceManager.get().registerGlobalScoped(CaptureClock.KEY, CaptureClock::get);

        LOGGER.info("{} 底座就绪（MC 客户端 setup 完成，纯客户端模组；T1.2~T1.5 设施已接入）",
                CaptureConstants.LOG_MARKER);
        CoreSelfTest.scheduleIfEnabled();
    }
}
