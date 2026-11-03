package com.captureapi.core.resource;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.thread.GlThreadGuard;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.chunk.ChunkSyncRequester;
import com.captureapi.video.chunk.SingleplayerTicketHolder;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界生命周期事件挂接（I1.2.2）。
 *
 * <p>主挂接：{@code ClientPlayerNetworkEvent.LoggingIn} → onWorldJoin；
 * {@code ClientPlayerNetworkEvent.LoggingOut} → onWorldLeave；
 * 兜底：{@code LevelEvent.Unload}（客户端世界卸载）→ onWorldLeave（双事件保险）。
 * 另加 tick 兜底：若世界已就绪但 {@code Minecraft.level == null}，补一次 onWorldLeave
 * （覆盖遗漏路径；与双事件幂等性配合，不会重复销毁）。</p>
 *
 * <p>线程：生命周期事件可能在客户端主线程（渲染线程）或其它线程触发；管理器要求
 * GL 资源操作在渲染线程执行，因此统一经 {@link GlThreadGuard#runOnRenderThread(Runnable)} 调度。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WorldLifecycleHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private WorldLifecycleHandler() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        schedule("LoggingIn", true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        schedule("LoggingOut", false);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            schedule("LevelUnload", false);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        WorldResourceManager manager = WorldResourceManager.get();
        if (manager.isWorldReady() && Minecraft.getInstance().level == null) {
            schedule("TickFallback", false);
        }
    }

    private static void schedule(String reason, boolean join) {
        WorldResourceManager manager = WorldResourceManager.get();
        Runnable task = join ? manager::onWorldJoin : () -> {
            // 缺陷1/3：退出世界时释放单机区块 ticket，并清空跨世界遗留相机（max_cameras 防绕过）
            SingleplayerTicketHolder.releaseAll();
            ChunkSyncRequester.releaseAll();
            CameraManager.get().clear();
            manager.onWorldLeave();
        };
        if (GlThreadGuard.isOnRenderThread()) {
            task.run();
        } else {
            // 事件在非渲染线程触发时投递到渲染线程；记录来源便于排查时序
            LOGGER.debug("{} 生命周期事件 {} 在非渲染线程触发，投递到渲染线程执行", CaptureConstants.LOG_MARKER, reason);
            GlThreadGuard.runOnRenderThread(task);
        }
    }
}
