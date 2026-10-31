package com.captureapi.video;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.video.encode.JpegEncoder;
import com.captureapi.video.render.OffscreenRenderPipeline;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M2 视频设施装配（T2.2.2 起）。
 *
 * <p>客户端 setup 阶段把离屏 FBO 注册为世界级资源（世界进入时创建、退出时销毁）；
 * 后续 T2.2.3/T2.3 的渲染管线在 {@link #onClientSetup} 中追加装配。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class VideoSetupHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private VideoSetupHandler() {
        throw new AssertionError("no instances");
    }

    /**
     * 客户端 setup：注册世界级离屏 FBO 资源。
     *
     * @param event Forge 客户端 setup 事件
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        WorldResourceManager.get().registerWorldScoped(OffscreenFboResource.KEY, OffscreenFboResource::new);
        WorldResourceManager.get().registerWorldScoped(PboPairResource.KEY, PboPairResource::new);
        JpegEncoder.prewarm();
        OffscreenRenderPipeline.install();
        LOGGER.info("{} 视频设施装配：离屏 FBO/PBO 对已注册（{}，{}），离屏渲染管线已挂接",
                CaptureConstants.LOG_MARKER, OffscreenFboResource.KEY, PboPairResource.KEY);
    }
}
