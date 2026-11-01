package com.captureapi.video.chunk;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.net.RemoteCaptureClient;
import com.captureapi.video.camera.CameraPose;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 摄制区块集合求取（I2.7.1）。
 *
 * <p>画面集合：相机视锥（投影×视图）与区块 AABB 求交（非方块范围框）；声音集合：以相机为中心
 * {@code sound_range} 水平半径内的区块；集合 = 画面 ∪ 声音。相机移动/转向/参数变化或
 * {@code chunk_rescan_ms} 到期才重算（节流）。</p>
 *
 * <p>本类仅做几何求取，不申请 ticket；单机 ticket 与远程 MP 申请分别在 T2.7.2/T2.7.3
 * （W-046~051 待 Spike）实现。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CaptureChunkPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final CaptureChunkPlanner INSTANCE = new CaptureChunkPlanner();

    /** 视锥候选扫描的最大区块半径（安全阀，防极端 far_plane 打爆计算量；实现细节，非加载范围框）。 */
    private static final int MAX_SCAN_RADIUS_CHUNKS = 64;

    private volatile CaptureChunkSet current = CaptureChunkSet.empty();

    private long lastScanNanos;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private float lastPitch;
    private float lastFov;
    private double lastFar;
    private double lastSoundRange;

    private CaptureChunkPlanner() {
    }

    public static CaptureChunkPlanner get() {
        return INSTANCE;
    }

    /** @return 最近一次求取的区块集合 */
    public CaptureChunkSet current() {
        return current;
    }

    /**
     * 相机帧更新入口（渲染线程每帧调用；内部节流）；变化时才重算。
     *
     * @param pose 当前帧相机位姿
     */
    public void update(CameraPose pose) {
        long now = com.captureapi.core.time.CaptureClock.get().nowNanos();
        CaptureParams params = CaptureParams.get();
        double soundRange = params.soundRange();
        double far = pose.farPlane();
        if (!needsRescan(now, pose, far, soundRange)) {
            return;
        }
        capture(pose, far, soundRange, now);
    }

    private boolean needsRescan(long nowNanos, CameraPose pose, double far, double soundRange) {
        if (current.isEmpty()) {
            return true;
        }
        long intervalNanos = Math.max(1L, CaptureParams.get().chunkRescanMs()) * 1_000_000L;
        if (nowNanos - lastScanNanos >= intervalNanos) {
            return true;
        }
        double dx = pose.eyePosition().x - lastX;
        double dy = pose.eyePosition().y - lastY;
        double dz = pose.eyePosition().z - lastZ;
        if (dx * dx + dy * dy + dz * dz >= 16.0D) {
            return true;
        }
        float yaw = pose.spec().yawDegrees();
        float pitch = pose.spec().pitchDegrees();
        float fov = (float) pose.spec().fovDegrees();
        return Math.abs(yaw - lastYaw) >= 5.0F || Math.abs(pitch - lastPitch) >= 5.0F
                || Math.abs(fov - lastFov) >= 1.0F || Math.abs(far - lastFar) > 0.5D
                || Math.abs(soundRange - lastSoundRange) > 0.5D;
    }

    private void capture(CameraPose pose, double farPlane, double soundRange, long nowNanos) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        int cameraChunkX = SectionPos.blockToSectionCoord((int) Math.floor(pose.eyePosition().x));
        int cameraChunkZ = SectionPos.blockToSectionCoord((int) Math.floor(pose.eyePosition().z));

        Set<Long> chunks = new HashSet<>();
        Matrix4f viewProjection = new Matrix4f(pose.projectionMatrix()).mul(pose.viewMatrix());
        FrustumIntersection frustum = new FrustumIntersection(viewProjection);

        // 画面集合：视锥与区块 AABB 求交
        int scanRadius = Math.min(MAX_SCAN_RADIUS_CHUNKS, (int) Math.ceil(farPlane / 16.0D) + 1);
        int soundRadius = (int) Math.ceil(soundRange / 16.0D);
        int radius = Math.max(scanRadius, soundRadius);
        for (int cx = cameraChunkX - radius; cx <= cameraChunkX + radius; cx++) {
            for (int cz = cameraChunkZ - radius; cz <= cameraChunkZ + radius; cz++) {
                boolean inSound = isWithinSoundRadius(cx, cz, cameraChunkX, cameraChunkZ, soundRadius);
                if (!inSound) {
                    float minX = cx << 4;
                    float minZ = cz << 4;
                    if (!frustum.testAab(minX, minY, minZ, minX + 16.0F, maxY, minZ + 16.0F)) {
                        continue;
                    }
                }
                chunks.add(ChunkPos.asLong(cx, cz));
            }
        }

        current = new CaptureChunkSet(chunks, chunks.size(), false, nowNanos);
        RemoteCaptureClient.onChunks(current.chunks());
        lastScanNanos = nowNanos;
        lastX = pose.eyePosition().x;
        lastY = pose.eyePosition().y;
        lastZ = pose.eyePosition().z;
        lastYaw = pose.spec().yawDegrees();
        lastPitch = pose.spec().pitchDegrees();
        lastFov = (float) pose.spec().fovDegrees();
        lastFar = farPlane;
        lastSoundRange = soundRange;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} 摄制区块集合重算：count={}（画面扫描半径={}，声音半径={}，far={}，sound_range={}）",
                    CaptureConstants.LOG_MARKER, chunks.size(), scanRadius, soundRadius, farPlane, soundRange);
        }
    }

    private static boolean isWithinSoundRadius(int cx, int cz, int centerX, int centerZ, int soundRadius) {
        if (soundRadius <= 0) {
            return false;
        }
        long dx = (long) cx - centerX;
        long dz = (long) cz - centerZ;
        return dx * dx + dz * dz <= (long) soundRadius * soundRadius;
    }

    /**
     * 摄制区块集合（不可变快照）。
     *
     * @param chunks        编码后的 ChunkPos long 集合（只读）
     * @param count         数量
     * @param truncated     是否被安全阀截断（保留字段）
     * @param timestampNanos 求取时刻
     */
    public record CaptureChunkSet(Set<Long> chunks, int count, boolean truncated, long timestampNanos) {

        public CaptureChunkSet {
            chunks = Collections.unmodifiableSet(chunks);
        }

        public static CaptureChunkSet empty() {
            return new CaptureChunkSet(Collections.emptySet(), 0, false, 0L);
        }

        public boolean isEmpty() {
            return count == 0;
        }
    }
}
