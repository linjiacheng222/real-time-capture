package com.captureapi.video.filter;

import com.captureapi.CaptureInterface;
import com.captureapi.audio.AudioFilterRegistry;
import com.captureapi.core.constant.CaptureConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** M8 名单设施装配（T8.3/T8.4）：客户端 setup 重载渲染/音频名单。 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ListSetupHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private ListSetupHandler() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        RenderFilterRegistry.get().reload();
        AudioFilterRegistry.get().reload();
        LOGGER.info("{} 名单设施装配完成", CaptureConstants.LOG_MARKER);
    }
}
