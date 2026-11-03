package com.captureapi.audio;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.audio.timestamp.AudioTimestamp;
import com.captureapi.audio.timestamp.AudioTimestampTracker;
import com.captureapi.core.time.CaptureClock;
import com.mojang.blaze3d.audio.Library;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.locks.LockSupport;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAL 环回捕获（T3.1 全链路）。
 *
 * <p>在 {@code Library.init} 的 Mixin 重定向中把真实输出设备替换为 ALC_SOFT_loopback，
 * 全部混音进入环回设备；独立采集线程（MAX-1 优先级）按 CaptureClock 节拍定长
 * {@code alcRenderSamplesSOFT} 拉取 PCM，先写环形缓冲（T3.2 接入点，当前预留给后续）
 * 再经 Java Sound {@code SourceDataLine} 中继到系统默认输出。</p>
 *
 * <p>探测/协商失败（扩展缺失、格式不支持、中继不可用、audio_enabled=false）→ 打开正常设备并
 * 禁用捕获，日志明示；绝不静默继续（R1）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AudioCaptureManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final AudioCaptureManager INSTANCE = new AudioCaptureManager();
    private static final String THREAD_NAME = "captureapi-audio-capture";
    /** ALC 环回格式属性个数（channels/type/frequency/结束哨兵）。 */
    private static final int FORMAT_ATTRIBUTE_COUNT = 9;

    private volatile boolean loopbackMode;
    private long loopbackDevice;
    private long context;
    private volatile boolean running;
    private Thread captureThread;
    private SourceDataLine speakerLine;
    private AudioFormat audioFormat;
    private int sampleRate;
    private int channels;
    private int pollIntervalMs;
    private long renderedSamples;
    private long renderedPolls;
    private long errorCount;
    private long shortPolls;

    private AudioCaptureManager() {
    }

    public static AudioCaptureManager get() {
        return INSTANCE;
    }

    /** @return 是否成功启用环回捕获（含中继） */
    public boolean isLoopbackActive() {
        return loopbackMode && running;
    }

    /**
     * 替代 {@code Library#openDeviceOrFallback}（Mixin Redirect）。
     *
     * @param requested 请求设备名
     * @return 设备句柄（环回或正常设备）
     */
    public long openDeviceOrFallback(String requested) {
        if (tryEnableLoopback()) {
            LOGGER.info("{} OpenAL 环回设备已启用：device={}，{}Hz/{}ch/16bit，poll={}ms",
                    CaptureConstants.LOG_MARKER, loopbackDevice, sampleRate, channels, pollIntervalMs);
            return loopbackDevice;
        }
        loopbackMode = false;
        long device = openNormalDevice(requested);
        LOGGER.info("{} OpenAL 环回不可用，使用正常设备（仅播放不采集）：device={}", CaptureConstants.LOG_MARKER, device);
        return device;
    }

    /**
     * 替代 {@code ALC10#alcCreateContext}（Mixin Redirect）；环回激活时注入格式属性。
     *
     * @param device 设备句柄
     * @param attrs  原属性（正常设备路径）
     * @return 上下文句柄
     */
    public long createContext(long device, IntBuffer attrs) {
        if (loopbackMode && device == loopbackDevice) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer format = stack.mallocInt(FORMAT_ATTRIBUTE_COUNT);
                format.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT)
                        .put(channels == 1 ? SOFTLoopback.ALC_MONO_SOFT : SOFTLoopback.ALC_STEREO_SOFT)
                        .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT)
                        .put(ALC10.ALC_FREQUENCY).put(sampleRate)
                        .put(0).flip();
                context = ALC10.alcCreateContext(device, format);
            }
            LOGGER.info("{} 环回上下文创建：context={}，{}Hz/{}ch/16bit", CaptureConstants.LOG_MARKER, context,
                    sampleRate, channels);
            return context;
        }
        context = ALC10.alcCreateContext(device, attrs);
        return context;
    }

    /** {@code Library#init} 完成后启动采集线程（Mixin Inject TAIL）。 */
    public void onLibraryInitialized() {
        if (!loopbackMode || context == 0L) {
            return;
        }
        if (!CaptureParams.get().audioEnabled()) {
            LOGGER.warn("{} audio_enabled=false，环回捕获不启动", CaptureConstants.LOG_MARKER);
            return;
        }
        startCaptureThread();
        if (Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_SPIKE_PROPERTY)) {
            Thread spike = new Thread(
                    com.captureapi.audio.multi.MultiListenerSpike::run,
                    "captureapi-multilistener-spike");
            spike.setDaemon(true);
            spike.start();
            LOGGER.info("{} 多 listener Phase 0 spike 已调度（player audio init 之后）",
                    CaptureConstants.LOG_MARKER);
        }
        if (Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_CHANNELS_PROPERTY)) {
            Thread test = new Thread(
                    () -> com.captureapi.audio.multi.AudioChannelRegistry.get().runSyntheticSelfTest(),
                    "captureapi-multilistener-chtest");
            test.setDaemon(true);
            test.start();
            LOGGER.info("{} Phase 1 多通道合成 self-test 已调度", CaptureConstants.LOG_MARKER);
        }
        if (Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_MIRROR_PROPERTY)) {
            Thread test = new Thread(
                    () -> com.captureapi.audio.multi.AudioChannelRegistry.get().runMirrorSelfTestIfEnabled(),
                    "captureapi-multilistener-mirrortest");
            test.setDaemon(true);
            test.start();
            LOGGER.info("{} Phase 2 静态镜像 self-test 已调度", CaptureConstants.LOG_MARKER);
        }
    }

    /** {@code Library#cleanup} 之前停止采集线程（Mixin Inject HEAD）。 */
    public void onLibraryCleanup() {
        stopCaptureThread();
        AudioRingBuffer.get().seal();
        loopbackMode = false;
        loopbackDevice = 0L;
        context = 0L;
    }

    private boolean tryEnableLoopback() {
        if (!CaptureParams.get().audioEnabled()) {
            return false;
        }
        if (!ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback")) {
            LOGGER.info("{} 驱动不支持 ALC_SOFT_loopback", CaptureConstants.LOG_MARKER);
            return false;
        }
        sampleRate = CaptureParams.get().sampleRate();
        channels = CaptureParams.get().channels();
        if (CaptureParams.get().bitDepth() != 16) {
            LOGGER.warn("{} 环回仅支持 16bit（当前 bit_depth={}）", CaptureConstants.LOG_MARKER,
                    CaptureParams.get().bitDepth());
            return false;
        }
        int channelCode = channels == 1 ? SOFTLoopback.ALC_MONO_SOFT : SOFTLoopback.ALC_STEREO_SOFT;
        long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
        if (device == 0L) {
            LOGGER.warn("{} alcLoopbackOpenDeviceSOFT 返回 0", CaptureConstants.LOG_MARKER);
            return false;
        }
        if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, sampleRate, channelCode,
                SOFTLoopback.ALC_SHORT_SOFT)) {
            LOGGER.warn("{} 环回格式不支持：{}Hz/{}ch/16bit", CaptureConstants.LOG_MARKER, sampleRate, channels);
            ALC10.alcCloseDevice(device);
            return false;
        }
        audioFormat = new AudioFormat(sampleRate, 16, channels, true, false);
        if (!AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, audioFormat))) {
            LOGGER.warn("{} Java Sound 中继线路不可用（无法同时播放/采集）", CaptureConstants.LOG_MARKER);
            ALC10.alcCloseDevice(device);
            return false;
        }
        pollIntervalMs = CaptureParams.get().pollIntervalMs();
        loopbackDevice = device;
        loopbackMode = true;
        return true;
    }

    private static long openNormalDevice(String requested) {
        long device = tryOpen(requested);
        if (device == 0L) {
            device = tryOpen(Library.getDefaultDeviceName());
        }
        if (device == 0L) {
            device = tryOpen(null);
        }
        if (device == 0L) {
            throw new IllegalStateException("Failed to open OpenAL device");
        }
        return device;
    }

    private static long tryOpen(String name) {
        long device = ALC10.alcOpenDevice(name);
        if (device != 0L && ALC10.alcGetError(device) == ALC10.ALC_NO_ERROR) {
            return device;
        }
        if (device != 0L) {
            ALC10.alcCloseDevice(device);
        }
        return 0L;
    }

    private void startCaptureThread() {
        if (running) {
            return;
        }
        running = true;
        Thread thread = new Thread(this::captureLoop, THREAD_NAME);
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                Math.min(Thread.MAX_PRIORITY - 1, CaptureParams.get().threadPriority())));
        captureThread = thread;
        thread.start();
    }

    private void captureLoop() {
        if (!openSpeakerLine()) {
            running = false;
            return;
        }
        try {
            ALC10.alcMakeContextCurrent(context);
        } catch (Throwable t) {
            LOGGER.error("{} 采集线程设置 OpenAL 上下文失败", CaptureConstants.LOG_MARKER, t);
            running = false;
            closeSpeakerLine();
            return;
        }
        int samplesPerPoll = Math.max(1, sampleRate * pollIntervalMs / 1000);
        int bytesPerPoll = samplesPerPoll * channels * 2;
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytesPerPoll).order(ByteOrder.nativeOrder());
        long startNanos = CaptureClock.get().nowNanos();
        long samplesRendered = 0L;
        LOGGER.info("{} 音频采集线程启动：{}，priority={}，每拍 {} 采样/{} bytes",
                CaptureConstants.LOG_MARKER, THREAD_NAME, Thread.currentThread().getPriority(), samplesPerPoll,
                bytesPerPoll);
        try {
            while (running) {
                long expected = startNanos + samplesRendered * 1_000_000_000L / sampleRate;
                long now = CaptureClock.get().nowNanos();
                long aheadNanos = expected - now;
                if (-aheadNanos > 500_000_000L) {
                    // 系统挂起/大幅落后：重同步，跳过积压
                    startNanos = now;
                    samplesRendered = 0L;
                    aheadNanos = 0L;
                }
                if (aheadNanos > 1_000_000L) {
                    LockSupport.parkNanos(Math.min(aheadNanos, 1_000_000L));
                    if (Thread.interrupted()) {
                        break;
                    }
                    continue;
                }

                buffer.clear();
                // P1.1/S2.4 修复：采集线程在 loopback render 前应用相机/玩家 listener
                AudioListenerSync.applyForCapture();
                SOFTLoopback.alcRenderSamplesSOFT(loopbackDevice, buffer, samplesPerPoll);
                int error = ALC10.alcGetError(loopbackDevice);
                if (error != ALC10.ALC_NO_ERROR) {
                    errorCount++;
                    if (errorCount % 100L == 1L) {
                        LOGGER.warn("{} 环回渲染错误：ALC 0x{}（累计 {}）", CaptureConstants.LOG_MARKER,
                                Integer.toHexString(error), errorCount);
                    }
                }
                buffer.position(0);
                byte[] pcm = new byte[bytesPerPoll];
                buffer.get(pcm, 0, bytesPerPoll);
                if (pcm.length != bytesPerPoll) {
                    shortPolls++;
                    LOGGER.warn("{} 环回短拍丢弃：len={}，期望={}", CaptureConstants.LOG_MARKER, pcm.length,
                            bytesPerPoll);
                } else {
                    // T3.4：渲染完成即打起始时间戳；终点纯整数推算，然后写环形缓冲再中继
                    long chunkStartNanos = CaptureClock.get().nowNanos();
                    long chunkEndNanos = AudioTimestamp.endNanos(chunkStartNanos, samplesPerPoll, sampleRate);
                    AudioRingBuffer.get().write(pcm, chunkStartNanos, chunkEndNanos, samplesPerPoll);
                    AudioTimestampTracker.get().onChunk(chunkStartNanos, chunkEndNanos,
                            CaptureClock.get().nowNanos());
                    relayToSpeaker(pcm);
                    renderedPolls++;
                }
                samplesRendered += samplesPerPoll;
                renderedSamples += samplesPerPoll;
            }
        } catch (Throwable t) {
            LOGGER.error("{} 音频采集线程异常退出（尝试恢复一次）", CaptureConstants.LOG_MARKER, t);
        } finally {
            try {
                ALC10.alcMakeContextCurrent(0L);
            } catch (Throwable ignored) {
                // 上下文可能已由 cleanup 销毁
            }
            closeSpeakerLine();
            running = false;
            LOGGER.info("{} 音频采集线程退出：polls={}，samples={}，errors={}，shortPolls={}",
                    CaptureConstants.LOG_MARKER, renderedPolls, renderedSamples, errorCount, shortPolls);
        }
    }

    private boolean openSpeakerLine() {
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            int samplesPerPoll = Math.max(1, sampleRate * pollIntervalMs / 1000);
            line.open(audioFormat, samplesPerPoll * channels * 2 * 8);
            line.start();
            speakerLine = line;
            return true;
        } catch (Throwable t) {
            LOGGER.error("{} 扬声器中继打开失败（禁用采集，避免静音继续）", CaptureConstants.LOG_MARKER, t);
            return false;
        }
    }

    private void relayToSpeaker(byte[] pcm) {
        SourceDataLine line = speakerLine;
        if (line == null) {
            return;
        }
        try {
            int written = line.write(pcm, 0, pcm.length);
            if (written < pcm.length) {
                shortPolls++;
            }
        } catch (Throwable t) {
            errorCount++;
            LOGGER.warn("{} 扬声器中继写入失败（连续错误将停止采集）", CaptureConstants.LOG_MARKER, t);
            if (errorCount % 10L == 0L) {
                running = false;
            }
        }
    }

    private void closeSpeakerLine() {
        SourceDataLine line = speakerLine;
        speakerLine = null;
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Throwable ignored) {
                // 关闭失败不阻断
            }
        }
    }

    private void stopCaptureThread() {
        running = false;
        Thread thread = captureThread;
        if (thread != null) {
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(2000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            captureThread = null;
        }
        closeSpeakerLine();
    }
}
