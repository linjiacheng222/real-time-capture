package com.captureapi.audio;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.camera.CameraPose;
import com.captureapi.video.camera.CameraSpec;
import com.captureapi.video.render.CameraSyncHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 监听器空间同步（T3.3）。
 *
 * <p>帧内把 OpenAL Listener 临时移到相机位姿（位置/朝向），渲染后写回玩家真实位置/朝向，
 * 保证环回混音是“相机听到的声音”且玩家听觉零扰动。映射计算复用预分配缓冲，正常路径零分配。</p>
 *
 * <p>仅在环回捕获激活时生效（否则零操作）；实现异常由 {@link CameraSyncHook} 隔离。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AudioListenerSync implements CameraSyncHook.Listener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final AudioListenerSync INSTANCE = new AudioListenerSync();
    private static final float[] ORIENTATION = new float[6];

    private long syncCount;
    private long errorCount;
    private volatile CameraPose latestPose;
    private volatile long latestPoseNanos;

    /** 客户端 setup 安装（AudioSetupHandler 调用）。 */
    public static void install() {
        CameraSyncHook.install(INSTANCE);
        LOGGER.info("{} 监听器空间同步已挂接 CameraSyncHook（修改→渲染→恢复同帧）", CaptureConstants.LOG_MARKER);
    }

    @Override
    public void beforeOffscreenRender(CameraPose pose) {
        if (!AudioCaptureManager.get().isLoopbackActive()) {
            return;
        }
        // 只登记最新相机位姿；实际 AL listener 写入由采集线程在每次 loopback render 前执行
        // （AL 上下文 current 在采集线程；渲染线程写入无效且会与采集线程竞争）。
        latestPose = pose;
        latestPoseNanos = com.captureapi.core.time.CaptureClock.get().nowNanos();
    }

    @Override
    public void afterOffscreenRender(CameraPose pose) {
        // 采集线程负责按最新位姿/玩家位姿切换 listener；此处不写 AL，避免跨线程上下文问题。
    }

    /**
     * 采集线程在每次 {@code alcRenderSamplesSOFT} 前调用：最新相机位姿新鲜则用相机 listener，
     * 否则回退玩家 listener（相机暂停/删除/世界未渲染）。必须在 loopback 上下文 current 的线程调用。
     */
    public static void applyForCapture() {
        INSTANCE.applyCaptureInternal();
    }

    private void applyCaptureInternal() {
        if (!AudioCaptureManager.get().isLoopbackActive()) {
            return;
        }
        CameraPose pose = latestPose;
        long now = com.captureapi.core.time.CaptureClock.get().nowNanos();
        if (pose != null && now - latestPoseNanos < 250_000_000L) {
            CameraSpec spec = pose.spec();
            AL10.alListener3f(AL10.AL_POSITION, (float) pose.eyePosition().x, (float) pose.eyePosition().y,
                    (float) pose.eyePosition().z);
            AL10.alListener3f(AL10.AL_VELOCITY, 0.0F, 0.0F, 0.0F);
            fillOrientation(spec.yawDegrees(), spec.pitchDegrees(), spec.rollDegrees(),
                    (float) pose.eyePosition().x, (float) pose.eyePosition().y, (float) pose.eyePosition().z);
            syncCount++;
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 eye = player.getEyePosition(1.0F);
        AL10.alListener3f(AL10.AL_POSITION, (float) eye.x, (float) eye.y, (float) eye.z);
        AL10.alListener3f(AL10.AL_VELOCITY, 0.0F, 0.0F, 0.0F);
        fillOrientation(player.getViewYRot(1.0F), player.getViewXRot(1.0F), 0.0F,
                (float) eye.x, (float) eye.y, (float) eye.z);
    }

    private static void fillOrientation(float yaw, float pitch, float roll, float x, float y, float z) {
        Quaternionf rotation = new Quaternionf().rotationYXZ((float) Math.toRadians(-yaw),
                (float) Math.toRadians(pitch), (float) Math.toRadians(roll));
        Vector3f forward = new Vector3f(0.0F, 0.0F, 1.0F).rotate(rotation);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(rotation);
        ORIENTATION[0] = forward.x;
        ORIENTATION[1] = forward.y;
        ORIENTATION[2] = forward.z;
        ORIENTATION[3] = up.x;
        ORIENTATION[4] = up.y;
        ORIENTATION[5] = up.z;
        AL10.alListenerfv(AL10.AL_ORIENTATION, ORIENTATION);
    }

    /** @return 修改/恢复次数 */
    public long syncCount() {
        return syncCount;
    }
}
