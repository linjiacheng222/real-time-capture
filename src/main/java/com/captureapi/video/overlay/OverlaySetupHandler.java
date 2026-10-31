package com.captureapi.video.overlay;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 叠加设施装配（T8.2）：客户端 setup 扫描贴图目录。 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OverlaySetupHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private OverlaySetupHandler() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        OverlayRegistry.get().reload();
        LOGGER.info("{} 叠加设施装配完成", CaptureConstants.LOG_MARKER);
    }
}
