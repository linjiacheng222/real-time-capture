package com.captureapi.net;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M4 网络设施装配（T4.1.2）。
 *
 * <p>客户端 setup 启动 HTTP/WS 服务；关闭由 {@link NetworkServiceManager} 的 JVM shutdown hook
 * 负责（幂等、端口释放）。世界登录/退出不重启网络服务。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class NetworkSetupHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private NetworkSetupHandler() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        NetworkServiceManager manager = NetworkServiceManager.get();
        manager.start();
        manager.registerHttpContext("/camera", new HttpVideoHandler(manager));
        manager.registerHttpContext("/camera/", new HttpVideoHandler(manager));
        LOGGER.info("{} 网络设施装配：服务启动请求已发出，视频路由已注册", CaptureConstants.LOG_MARKER);
    }
}
