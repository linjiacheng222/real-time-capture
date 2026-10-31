package com.captureapi.audio;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.WorldResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M3 音频设施装配（T3.2.6）。
 *
 * <p>客户端 setup 把环形音频缓冲登记为全局级资源（跨世界存续，游戏退出释放）；
 * 采集线程在 {@code Library#init} 后写入该缓冲，M4 消费端读同一实例。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AudioSetupHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private AudioSetupHandler() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        WorldResourceManager.get().registerGlobalScoped(AudioRingBuffer.KEY, AudioRingBuffer::get);
        AudioListenerSync.install();
        LOGGER.info("{} 音频设施装配：环形缓冲已注册（{}）", CaptureConstants.LOG_MARKER,
                AudioRingBuffer.KEY);
    }
}
