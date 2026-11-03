package com.captureapi.audio.multi;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.time.CaptureClock;
import com.captureapi.audio.AudioRingBuffer;
import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多 listener 音频通道注册表（Phase 1）。
 *
 * <p>Phase 1 只负责通道/worker/ring 生命周期与合成 self-test；Phase 4 才接相机/API/WS。
 * 所有通道都在玩家 audio 初始化后创建，避免 LWJGL AL capabilities 竞态。</p>
 */
public final class AudioChannelRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final AudioChannelRegistry INSTANCE = new AudioChannelRegistry();

    private static final int VIRTUAL_CHANNEL_ID = -1;
    private static final Object VIRTUAL_LOCK = new Object();
    private final Map<Integer, AudioListenerChannel> channels = new ConcurrentHashMap<>();
    private final AtomicLong createdTotal = new AtomicLong();
    private final AtomicLong destroyedTotal = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile boolean installed;

    private AudioChannelRegistry() {
    }

    public static AudioChannelRegistry get() {
        return INSTANCE;
    }

    /** 客户端 setup 安装（幂等；不创建通道）。 */
    public synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        LOGGER.info("{} [AUDIO/CH] 多 listener 通道注册表已安装（enabled={}，maxListenerChannels={}）",
                CaptureConstants.LOG_MARKER, CaptureParams.get().audioMultiListenerEnabled(),
                CaptureParams.get().audioMaxListenerChannels());
    }

    /** @return 多 listener 功能是否开启 */
    public boolean enabled() {
        return CaptureParams.get().audioMultiListenerEnabled()
                || Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_FEATURE_PROPERTY);
    }

    /** 相机通道便捷创建（无测试音）。 */
    public AudioListenerChannel createCameraChannel(int cameraId, AudioListenerPose pose) {
        return createCameraChannel(cameraId, pose, 0.0D, 0.0D);
    }

    /**
     * 相机通道确保存在并更新 listener pose（Phase 4 coordinator 调用）。
     */
    public AudioListenerChannel ensureCameraChannel(int cameraId, AudioListenerPose pose) {
        if (!enabled()) {
            return null;
        }
        AudioListenerChannel channel = channels.get(cameraId);
        if (channel != null && channel.kind() == AudioChannelKind.CAMERA) {
            channel.setListenerPose(pose);
            return channel;
        }
        AudioListenerChannel created = createCameraChannel(cameraId, pose);
        if (created != null) {
            created.setListenerPose(pose);
        }
        return created;
    }

    /**
     * 确保虚拟 listener 通道存在（audio section 专用）。id=-1，不计入相机上限。
     */
    public AudioListenerChannel ensureVirtualChannel() {
        if (!enabled()) {
            return null;
        }
        AudioListenerChannel existing = channels.get(VIRTUAL_CHANNEL_ID);
        if (existing != null && existing.active()) {
            return existing;
        }
        AudioPcmRing ring = newRing();
        AudioContextWorker worker = new AudioContextWorker(
                "captureapi-audio-virtual", ring, ring.sampleRate(), ring.channels(),
                CaptureParams.get().pollIntervalMs());
        AudioListenerChannel channel = new AudioListenerChannel(VIRTUAL_CHANNEL_ID,
                AudioChannelKind.VIRTUAL, -1, ring, worker);
        if (!channel.start(0.0D, 0.0D)) {
            failures.incrementAndGet();
            return null;
        }
        channels.put(VIRTUAL_CHANNEL_ID, channel);
        createdTotal.incrementAndGet();
        LOGGER.info("{} [AUDIO/CH] 虚拟 listener 通道启动：id=-1", CaptureConstants.LOG_MARKER);
        return channel;
    }

    /** 仅保留给定 id 的相机通道（虚拟/player 不动）。 */
    public void retainCameraChannels(Set<Integer> cameraIds) {
        for (Integer id : new ArrayList<>(channels.keySet())) {
            AudioListenerChannel channel = channels.get(id);
            if (channel != null && channel.kind() == AudioChannelKind.CAMERA
                    && !cameraIds.contains(id)) {
                removeChannel(id);
            }
        }
    }

    /** 删除所有相机/虚拟 listener 通道（世界退出）。 */
    public synchronized void removeWorldChannels() {
        for (Integer id : new ArrayList<>(channels.keySet())) {
            AudioListenerChannel channel = channels.get(id);
            if (channel != null && channel.kind() != AudioChannelKind.PLAYER) {
                removeChannel(id);
            }
        }
    }

    /**
     * Phase 4 音频截面：虚拟 listener 采样，不移动 player listener。
     *
     * @return PCM chunk；feature 关闭/失败返回 null
     */
    public AudioChunk grabVirtualSection(double x, double y, double z, int chunks) {
        if (!enabled() || chunks < 1) {
            return null;
        }
        AudioListenerChannel virtual = ensureVirtualChannel();
        if (virtual == null) {
            return null;
        }
        synchronized (VIRTUAL_LOCK) {
            virtual.setListenerPose(new AudioListenerPose(x, y, z, 0.0F, 0.0F, 0.0F));
            long waitNanos = (long) chunks * CaptureParams.get().pollIntervalMs()
                    * CaptureConstants.NANOS_PER_MILLI + CaptureConstants.AUDIO_SECTION_MARGIN_MS
                    * CaptureConstants.NANOS_PER_MILLI;
            long deadline = CaptureClock.get().nowNanos() + Math.max(1L, waitNanos);
            while (CaptureClock.get().nowNanos() < deadline) {
                try {
                    Thread.sleep(CaptureParams.get().pollIntervalMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return virtual.ring().snapshotRecent(chunks);
        }
    }

    /**
     * 主出口读取：audio_primary_channel=camera 时读 active 相机 ring，否则读 player ring。
     */
    public AudioChunk readPrimaryChunk(long timeoutMillis) {
        if (enabled() && "camera".equalsIgnoreCase(CaptureParams.get().audioPrimaryChannel())) {
            int activeCamera = CameraManager.get().activeCameraId();
            AudioListenerChannel camera = activeCamera > 0 ? channels.get(activeCamera) : null;
            if (camera != null && camera.active()) {
                return camera.ring().readChunk(timeoutMillis);
            }
        }
        AudioRingBuffer.ReadChunkResult result = AudioRingBuffer.get().readChunk(timeoutMillis);
        return result.status() == AudioRingBuffer.ReadStatus.DATA ? result.chunk() : null;
    }

    /** @return 主出口通道 id（0=player，>0=camera） */
    public int primaryChannelId() {
        if (enabled() && "camera".equalsIgnoreCase(CaptureParams.get().audioPrimaryChannel())) {
            int activeCamera = CameraManager.get().activeCameraId();
            if (activeCamera > 0 && channels.containsKey(activeCamera)) {
                return activeCamera;
            }
        }
        return 0;
    }

    /** 快照指定通道最近 chunks 拍（录像/调试；不消费）。 */
    public AudioChunk snapshotChannel(int channelId, int chunks) {
        if (channelId == 0) {
            return AudioRingBuffer.get().snapshotRecent(chunks);
        }
        AudioListenerChannel channel = channels.get(channelId);
        return channel == null ? null : channel.ring().snapshotRecent(chunks);
    }

    /** @return 指定通道最新 PCM（不消费）。 */
    public AudioChunk latestChunk(int channelId) {
        if (channelId == 0) {
            return AudioRingBuffer.get().peekLatest();
        }
        AudioListenerChannel channel = channels.get(channelId);
        return channel == null ? null : channel.ring().peekLatest();
    }

    /** 创建并启动相机 listener 通道（Phase 4 使用；Phase 1 self-test 也用）。 */
    public AudioListenerChannel createCameraChannel(int cameraId, AudioListenerPose pose,
                                                    double testFrequency, double testX) {
        if (!enabled() && !Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_CHANNELS_PROPERTY)
                && !Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_MIRROR_PROPERTY)
                && !Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_STREAM_PROPERTY)) {
            return null;
        }
        int existing = channels.size();
        if (existing >= CaptureParams.get().audioMaxListenerChannels()) {
            failures.incrementAndGet();
            LOGGER.warn("{} [AUDIO/CH] listener 通道上限已到：{}", CaptureConstants.LOG_MARKER,
                    CaptureParams.get().audioMaxListenerChannels());
            return null;
        }
        AudioPcmRing ring = newRing();
        AudioContextWorker worker = new AudioContextWorker(
                "captureapi-audio-ch-" + cameraId, ring,
                ring.sampleRate(), ring.channels(), CaptureParams.get().pollIntervalMs());
        AudioListenerChannel channel = new AudioListenerChannel(cameraId, AudioChannelKind.CAMERA,
                cameraId, ring, worker);
        channel.setListenerPose(pose);
        if (!channel.start(testFrequency, testX)) {
            failures.incrementAndGet();
            return null;
        }
        AudioListenerChannel previous = channels.putIfAbsent(cameraId, channel);
        if (previous != null) {
            channel.stop();
            return previous;
        }
        createdTotal.incrementAndGet();
        LOGGER.info("{} [AUDIO/CH] 相机通道启动：camera={}，ring={}B，testTone={}Hz",
                CaptureConstants.LOG_MARKER, cameraId, ring.slotBytes(), testFrequency);
        return channel;
    }

    /** 删除并停止相机通道。 */
    public void removeChannel(int channelId) {
        AudioListenerChannel channel = channels.remove(channelId);
        if (channel != null) {
            channel.stop();
            destroyedTotal.incrementAndGet();
            LOGGER.info("{} [AUDIO/CH] 通道关闭：id={}", CaptureConstants.LOG_MARKER, channelId);
        }
    }

    /** 停止全部通道（世界退出/关闭）。 */
    public synchronized void stopAll() {
        for (Integer id : new ArrayList<>(channels.keySet())) {
            removeChannel(id);
        }
    }

    /** @return 供镜像层使用的活动 listener 通道。 */
    public List<AudioListenerChannel> mirrorChannels() {
        List<AudioListenerChannel> result = new ArrayList<>();
        for (AudioListenerChannel channel : channels.values()) {
            if (channel.active()) {
                result.add(channel);
            }
        }
        return result;
    }

    /**
     * Phase 2 dev self-test：创建 2 个 listener channel，播放一个位置测试音，
     * 等待静态 buffer 镜像后读取两路 ring。仅 dev 属性触发。
     */
    public boolean runMirrorSelfTestIfEnabled() {
        if (!Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_MIRROR_PROPERTY)) {
            return true;
        }
        LOGGER.info("{} [AUDIO/MIRROR] Phase 2 静态声源镜像 self-test 开始", CaptureConstants.LOG_MARKER);
        AudioListenerChannel first = null;
        AudioListenerChannel second = null;
        try {
            first = createCameraChannel(1, new AudioListenerPose(0.0D, 64.0D, 0.0D, 0.0F, 0.0F, 0.0F),
                    0.0D, 0.0D);
            second = createCameraChannel(2, new AudioListenerPose(
                    CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X, 64.0D, 0.0D, 0.0F, 0.0F, 0.0F),
                    0.0D, 0.0D);
            if (first == null || second == null) {
                failures.incrementAndGet();
                LOGGER.error("{} [AUDIO/MIRROR] self-test FAIL：通道创建失败", CaptureConstants.LOG_MARKER);
                return false;
            }
            Thread.sleep(CaptureConstants.MULTILISTENER_MIRROR_STARTUP_WAIT_MS);
            AudioSourceMirror.resetPlayCommands();
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            minecraft.execute(() -> {
                minecraft.options.pauseOnLostFocus = false;
                if (minecraft.screen != null && minecraft.screen.isPauseScreen()) {
                    minecraft.setScreen(null);
                }
                minecraft.getSoundManager().resume();
                minecraft.getSoundManager().play(
                        new net.minecraft.client.resources.sounds.SimpleSoundInstance(
                                net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BELL.value(),
                                net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F,
                                net.minecraft.util.RandomSource.create(),
                                CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X, 64.0D, 0.0D));
            });
            long deadline = CaptureClock.get().nowNanos()
                    + CaptureConstants.MULTILISTENER_MIRROR_STARTUP_WAIT_MS
                    * CaptureConstants.NANOS_PER_MILLI;
            while (AudioSourceMirror.playCommands() == 0L
                    && CaptureClock.get().nowNanos() < deadline) {
                Thread.sleep(10L);
            }
            Thread.sleep(CaptureConstants.MULTILISTENER_MIRROR_TEST_MS);
            var a = first.ring().snapshotRecent(5);
            var b = second.ring().snapshotRecent(5);
            if (a == null || b == null) {
                failures.incrementAndGet();
                LOGGER.error("{} [AUDIO/MIRROR] self-test FAIL：ring 快照为空", CaptureConstants.LOG_MARKER);
                return false;
            }
            boolean nonSilent = rms(a.pcm()) > CaptureConstants.MULTILISTENER_MIRROR_MIN_RMS
                    && rms(b.pcm()) > CaptureConstants.MULTILISTENER_MIRROR_MIN_RMS;
            boolean different = !Arrays.equals(a.pcm(), b.pcm());
            LOGGER.info("{} [AUDIO/MIRROR] self-test samplesA={} samplesB={} rmsA={} rmsB={} "
                            + "nonSilent={} different={}",
                    CaptureConstants.LOG_MARKER, a.sampleCount(), b.sampleCount(), rms(a.pcm()),
                    rms(b.pcm()), nonSilent, different);
            if (nonSilent && different) {
                LOGGER.info("{} [AUDIO/MIRROR] self-test PASS：静态声源镜像到双 listener",
                        CaptureConstants.LOG_MARKER);
                return true;
            }
            LOGGER.error("{} [AUDIO/MIRROR] self-test FAIL：未满足非静音/差异", CaptureConstants.LOG_MARKER);
            return false;
        } catch (Throwable t) {
            failures.incrementAndGet();
            LOGGER.error("{} [AUDIO/MIRROR] self-test FAIL：{}", CaptureConstants.LOG_MARKER, t.toString());
            return false;
        } finally {
            if (first != null) {
                removeChannel(first.id());
            }
            if (second != null) {
                removeChannel(second.id());
            }
        }
    }

    public List<AudioChannelInfo> channels() {
        List<AudioChannelInfo> result = new ArrayList<>();
        result.add(new AudioChannelInfo(0, AudioChannelKind.PLAYER, -1, true,
                AudioRingBuffer.get().writtenPolls(), AudioRingBuffer.get().droppedPolls(), 0L));
        for (AudioListenerChannel channel : channels.values()) {
            result.add(channel.info());
        }
        return List.copyOf(result);
    }

    public int activeChannelCount() {
        int count = 0;
        for (AudioListenerChannel channel : channels.values()) {
            if (channel.active()) {
                count++;
            }
        }
        return count;
    }

    public long createdTotal() {
        return createdTotal.get();
    }

    public long destroyedTotal() {
        return destroyedTotal.get();
    }

    public long failures() {
        return failures.get();
    }

    public long writtenTotal() {
        long total = 0L;
        for (AudioListenerChannel channel : channels.values()) {
            total += channel.ring().writtenPolls();
        }
        return total;
    }

    public long droppedTotal() {
        long total = 0L;
        for (AudioListenerChannel channel : channels.values()) {
            total += channel.ring().droppedPolls();
        }
        return total;
    }

    /**
     * Phase 3 dev self-test：两个 listener channel，播放流式音乐，等待 tee 数据后采样。
     */
    public boolean runStreamSelfTestIfEnabled() {
        if (!Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_STREAM_PROPERTY)) {
            return true;
        }
        LOGGER.info("{} [AUDIO/STREAM] Phase 3 流式镜像 self-test 开始", CaptureConstants.LOG_MARKER);
        AudioListenerChannel first = null;
        AudioListenerChannel second = null;
        try {
            first = createCameraChannel(1, new AudioListenerPose(0.0D, 64.0D, 0.0D, 0.0F, 0.0F, 0.0F),
                    0.0D, 0.0D);
            second = createCameraChannel(2, new AudioListenerPose(
                    CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X, 64.0D, 0.0D, 0.0F, 0.0F, 0.0F),
                    0.0D, 0.0D);
            if (first == null || second == null) {
                failures.incrementAndGet();
                LOGGER.error("{} [AUDIO/STREAM] self-test FAIL：通道创建失败", CaptureConstants.LOG_MARKER);
                return false;
            }
            Thread.sleep(CaptureConstants.MULTILISTENER_MIRROR_STARTUP_WAIT_MS);
            AudioSourceMirror.resetPlayCommands();
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            minecraft.execute(() -> {
                minecraft.options.pauseOnLostFocus = false;
                if (minecraft.screen != null && minecraft.screen.isPauseScreen()) {
                    minecraft.setScreen(null);
                }
                minecraft.getSoundManager().resume();
                minecraft.getSoundManager().play(
                        new net.minecraft.client.resources.sounds.SimpleSoundInstance(
                                net.minecraft.sounds.SoundEvents.MUSIC_DISC_CAT,
                                net.minecraft.sounds.SoundSource.RECORDS, 1.0F, 1.0F,
                                net.minecraft.util.RandomSource.create(),
                                CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X, 64.0D, 0.0D));
            });
            long deadline = CaptureClock.get().nowNanos()
                    + CaptureConstants.MULTILISTENER_MIRROR_STARTUP_WAIT_MS
                    * CaptureConstants.NANOS_PER_MILLI;
            while (AudioSourceMirror.streamDataCommands() == 0L
                    && CaptureClock.get().nowNanos() < deadline) {
                Thread.sleep(10L);
            }
            Thread.sleep(CaptureConstants.MULTILISTENER_STREAM_TEST_MS);
            var a = first.ring().snapshotRecent(5);
            var b = second.ring().snapshotRecent(5);
            boolean dataFlow = AudioSourceMirror.streamDataCommands() > 0L;
            if (a == null || b == null) {
                failures.incrementAndGet();
                LOGGER.error("{} [AUDIO/STREAM] self-test FAIL：ring 快照为空，streamData={}",
                        CaptureConstants.LOG_MARKER, dataFlow);
                return false;
            }
            boolean nonSilent = rms(a.pcm()) > CaptureConstants.MULTILISTENER_STREAM_MIN_RMS
                    && rms(b.pcm()) > CaptureConstants.MULTILISTENER_STREAM_MIN_RMS;
            boolean different = !Arrays.equals(a.pcm(), b.pcm());
            LOGGER.info("{} [AUDIO/STREAM] self-test dataFlow={} samplesA={} samplesB={} "
                            + "rmsA={} rmsB={} nonSilent={} different={}",
                    CaptureConstants.LOG_MARKER, dataFlow, a.sampleCount(), b.sampleCount(),
                    rms(a.pcm()), rms(b.pcm()), nonSilent, different);
            if (dataFlow && nonSilent && different) {
                LOGGER.info("{} [AUDIO/STREAM] self-test PASS：流式 PCM 镜像到双 listener",
                        CaptureConstants.LOG_MARKER);
                return true;
            }
            LOGGER.error("{} [AUDIO/STREAM] self-test FAIL：dataFlow={} nonSilent={} different={}",
                    CaptureConstants.LOG_MARKER, dataFlow, nonSilent, different);
            return false;
        } catch (Throwable t) {
            failures.incrementAndGet();
            LOGGER.error("{} [AUDIO/STREAM] self-test FAIL：{}", CaptureConstants.LOG_MARKER, t.toString());
            return false;
        } finally {
            if (first != null) {
                removeChannel(first.id());
            }
            if (second != null) {
                removeChannel(second.id());
            }
        }
    }

    /**
     * Phase 1 开发 self-test：两个合成 camera 通道，验证独立 PCM/线程/ring。
     * 由 dev 属性触发，不进入正式 API。
     */
    public boolean runSyntheticSelfTest() {
        if (!Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_CHANNELS_PROPERTY)) {
            return true;
        }
        LOGGER.info("{} [AUDIO/CH] Phase 1 合成通道 self-test 开始", CaptureConstants.LOG_MARKER);
        AudioListenerChannel first = null;
        AudioListenerChannel second = null;
        try {
            first = createCameraChannel(1, new AudioListenerPose(0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F),
                    CaptureConstants.MULTILISTENER_SPIKE_FREQUENCY,
                    CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X);
            second = createCameraChannel(2,
                    new AudioListenerPose(CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X, 0.0D, 0.0D,
                            0.0F, 0.0F, 0.0F),
                    CaptureConstants.MULTILISTENER_SPIKE_FREQUENCY * 2.0D,
                    CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X);
            if (first == null || second == null) {
                failures.incrementAndGet();
                LOGGER.error("{} [AUDIO/CH] self-test FAIL：通道创建失败", CaptureConstants.LOG_MARKER);
                return false;
            }
            Thread.sleep(CaptureConstants.MULTILISTENER_CHANNEL_TEST_MS);
            var a = first.ring().snapshotRecent(5);
            var b = second.ring().snapshotRecent(5);
            if (a == null || b == null) {
                failures.incrementAndGet();
                LOGGER.error("{} [AUDIO/CH] self-test FAIL：ring 快照为空", CaptureConstants.LOG_MARKER);
                return false;
            }
            boolean nonSilent = rms(a.pcm()) > CaptureConstants.MULTILISTENER_MIRROR_MIN_RMS
                    && rms(b.pcm()) > CaptureConstants.MULTILISTENER_MIRROR_MIN_RMS;
            boolean different = !Arrays.equals(a.pcm(), b.pcm());
            boolean mono = a.startNanos() > 0L && b.startNanos() > 0L;
            LOGGER.info("{} [AUDIO/CH] self-test samplesA={} samplesB={} rmsA={} rmsB={} "
                            + "nonSilent={} different={} timestamps={}",
                    CaptureConstants.LOG_MARKER, a.sampleCount(), b.sampleCount(), rms(a.pcm()),
                    rms(b.pcm()), nonSilent, different, mono);
            if (nonSilent && different && mono) {
                LOGGER.info("{} [AUDIO/CH] self-test PASS：双通道独立 PCM/ring/时间戳",
                        CaptureConstants.LOG_MARKER);
                return true;
            }
            LOGGER.error("{} [AUDIO/CH] self-test FAIL：不满足非静音/独立/时间戳", CaptureConstants.LOG_MARKER);
            return false;
        } catch (Throwable t) {
            failures.incrementAndGet();
            LOGGER.error("{} [AUDIO/CH] self-test FAIL：{}", CaptureConstants.LOG_MARKER, t.toString());
            return false;
        } finally {
            if (first != null) {
                removeChannel(first.id());
            }
            if (second != null) {
                removeChannel(second.id());
            }
        }
    }

    private static AudioPcmRing newRing() {
        return new AudioPcmRing(CaptureParams.get().sampleRate(), CaptureParams.get().channels(),
                CaptureParams.get().pollIntervalMs(), CaptureParams.get().ringCapacitySec());
    }

    private static double rms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) {
            return 0.0D;
        }
        double sum = 0.0D;
        int count = pcm.length / 2;
        for (int i = 0; i < count; i++) {
            short sample = (short) (((pcm[i * 2 + 1] & 0xFF) << 8) | (pcm[i * 2] & 0xFF));
            double value = sample / (double) Short.MAX_VALUE;
            sum += value * value;
        }
        return Math.sqrt(sum / count);
    }
}
