package com.captureapi.audio.multi;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraSpec;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 相机/虚拟 listener 通道协调器（Phase 4）。
 *
 * <p>客户端 tick 低频同步 CameraManager：为相机建立/更新 listener channel，删除缺失通道，
 * 并确保 virtual channel 存在。feature 关闭时零行为。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AudioChannelCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static long lastSyncNanos;

    private AudioChannelCoordinator() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        AudioChannelRegistry registry = AudioChannelRegistry.get();
        if (!registry.enabled()) {
            return;
        }
        long now = CaptureClock.get().nowNanos();
        if (now - lastSyncNanos < CaptureConstants.AUDIO_CHANNEL_SYNC_INTERVAL_MS
                * CaptureConstants.NANOS_PER_MILLI) {
            return;
        }
        lastSyncNanos = now;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            registry.removeWorldChannels();
            return;
        }
        List<CameraSpec> cameras = CameraManager.get().list();
        Set<Integer> ids = new HashSet<>();
        for (CameraSpec spec : cameras) {
            if (spec.paused()) {
                continue;
            }
            ids.add(spec.id());
            registry.ensureCameraChannel(spec.id(), new AudioListenerPose(
                    spec.positionX(), spec.positionY(), spec.positionZ(),
                    spec.yawDegrees(), spec.pitchDegrees(), spec.rollDegrees()));
        }
        registry.retainCameraChannels(ids);
        registry.ensureVirtualChannel();
    }
}
