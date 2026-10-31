package com.captureapi.core;

import com.captureapi.CaptureInterface;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端生命周期挂载（I1.1.2）。
 *
 * <p>纯客户端模组：所有事件订阅类必须显式声明 {@code value = Dist.CLIENT}，防止在
 * 物理服务端歧义加载；本类只在客户端 setup 阶段打印一次基线就绪日志，后续 T1.2/T1.3
 * 的服务装配点在此集中接入。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetupHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private ClientSetupHandler() {
        throw new AssertionError("no instances");
    }

    /**
     * 客户端 setup 基线日志（验收标准 I1.1.2⑧：日志出现带 {@code [CaptureAPI]} 前缀的就绪行）。
     *
     * @param event Forge 客户端 setup 事件
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("{} 底座就绪（MC 客户端 setup 完成，纯客户端模组；后续 T1.2/T1.3/T1.5 服务在此装配）",
                CaptureConstants.LOG_MARKER);
    }
}
