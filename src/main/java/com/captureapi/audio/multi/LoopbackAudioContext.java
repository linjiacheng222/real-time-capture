package com.captureapi.audio.multi;

import com.captureapi.core.constant.CaptureConstants;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTLoopback;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 独立 loopback device/context（多 listener 音频基础）。
 *
 * <p>每个实例拥有自己的 OpenAL device、context 和 listener，只有持有线程可以
 * {@code alcMakeContextCurrent}。Phase 0 用于验证同进程多 loopback device 可行性，
 * Phase 1 起作为 camera/virtual channel 的底层上下文。</p>
 */
public final class LoopbackAudioContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final int FORMAT_ATTRIBUTE_COUNT = 9;

    private final int sampleRate;
    private final int channels;
    private long device;
    private long context;
    private boolean open;
    private boolean threadLocalContext;

    public LoopbackAudioContext(int sampleRate, int channels) {
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * 打开 device/context 并设为当前上下文。
     *
     * @return 成功返回 true；扩展/格式/设备失败返回 false
     */
    public boolean open() {
        if (open) {
            return true;
        }
        if (!ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback")) {
            LOGGER.warn("{} [AUDIO/CTX] ALC_SOFT_loopback 不可用", CaptureConstants.LOG_MARKER);
            return false;
        }
        int channelCode = channels == 1 ? SOFTLoopback.ALC_MONO_SOFT : SOFTLoopback.ALC_STEREO_SOFT;
        device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
        if (device == 0L) {
            LOGGER.warn("{} [AUDIO/CTX] alcLoopbackOpenDeviceSOFT 返回 0", CaptureConstants.LOG_MARKER);
            return false;
        }
        // 先创建并切换上下文，再建立 LWJGL 线程 capabilities；ALC.createCapabilities(device)
        // 需要当前线程有 OpenAL context。函数 provider 由 Minecraft 的 Library.init 已创建。
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer attrs = stack.mallocInt(FORMAT_ATTRIBUTE_COUNT);
            attrs.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(channelCode)
                    .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT)
                    .put(ALC10.ALC_FREQUENCY).put(sampleRate)
                    .put(0).flip();
            context = ALC10.alcCreateContext(device, attrs);
        }
        if (context == 0L) {
            ALC10.alcCloseDevice(device);
            device = 0L;
            LOGGER.warn("{} [AUDIO/CTX] alcCreateContext 返回 0", CaptureConstants.LOG_MARKER);
            return false;
        }
        // 优先使用线程本地上下文，避免把进程/其他线程的 current context 切走
        threadLocalContext = ALC10.alcIsExtensionPresent(device, "ALC_EXT_thread_local_context")
                && EXTThreadLocalContext.alcSetThreadContext(context);
        if (!threadLocalContext && !ALC10.alcMakeContextCurrent(context)) {
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
            context = 0L;
            device = 0L;
            LOGGER.warn("{} [AUDIO/CTX] 设置上下文失败（thread-local={}）",
                    CaptureConstants.LOG_MARKER, threadLocalContext);
            return false;
        }
        try {
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            ALCapabilities alCaps = AL.createCapabilities(alcCaps);
            if (alcCaps == null || alCaps == null) {
                throw new IllegalStateException("ALC/AL capabilities 创建返回 null");
            }
        } catch (Throwable t) {
            releaseCurrentContext();
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
            context = 0L;
            device = 0L;
            threadLocalContext = false;
            LOGGER.warn("{} [AUDIO/CTX] OpenAL capabilities 初始化失败：{}",
                    CaptureConstants.LOG_MARKER, t.toString());
            return false;
        }
        if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, sampleRate, channelCode,
                SOFTLoopback.ALC_SHORT_SOFT)) {
            releaseCurrentContext();
            ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
            context = 0L;
            device = 0L;
            threadLocalContext = false;
            LOGGER.warn("{} [AUDIO/CTX] 环回格式不支持：{}Hz/{}ch/16bit",
                    CaptureConstants.LOG_MARKER, sampleRate, channels);
            return false;
        }
        open = true;
        return true;
    }

    /** 当前上下文置空并销毁 device/context。 */
    public void close() {
        if (!open) {
            return;
        }
        try {
            releaseCurrentContext();
        } catch (Throwable ignored) {
            // 上下文可能已丢失
        }
        if (context != 0L) {
            ALC10.alcDestroyContext(context);
            context = 0L;
        }
        if (device != 0L) {
            ALC10.alcCloseDevice(device);
            device = 0L;
        }
        open = false;
        threadLocalContext = false;
    }

    private void releaseCurrentContext() {
        if (threadLocalContext) {
            EXTThreadLocalContext.alcSetThreadContext(0L);
        } else {
            ALC10.alcMakeContextCurrent(0L);
        }
    }

    /** @return 当前 context 是否打开 */
    public boolean isOpen() {
        return open;
    }

    /** @return OpenAL device 句柄（诊断） */
    public long device() {
        return device;
    }

    /** @return OpenAL context 句柄（诊断） */
    public long context() {
        return context;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    /** 生成一个循环正弦测试源（Phase 0/1 使用）。 */
    public int createSineSource(float frequencyHz, float x) {
        int samples = CaptureConstants.MULTILISTENER_SPIKE_SAMPLES;
        // 源必须是 mono 才会被 OpenAL 空间化；stereo buffer 原样输出，无法验证 listener 差异。
        ByteBuffer pcm = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder());
        for (int i = 0; i < samples; i++) {
            double angle = 2.0D * Math.PI * frequencyHz * i / sampleRate;
            short value = (short) (Math.sin(angle) * Short.MAX_VALUE / 2);
            pcm.putShort(value);
        }
        pcm.flip();
        int format = AL10.AL_FORMAT_MONO16;
        int buffer = AL10.alGenBuffers();
        AL10.alBufferData(buffer, format, pcm, sampleRate);
        int source = AL10.alGenSources();
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcef(source, AL10.AL_GAIN, 1.0F);
        AL10.alSource3f(source, AL10.AL_POSITION, x, 0.0F, 0.0F);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_TRUE);
        AL10.alSourcePlay(source);
        return source;
    }

    /** 渲染指定采样数到堆内 PCM。 */
    public byte[] render(int samples) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(samples * channels * 2)
                .order(ByteOrder.nativeOrder());
        SOFTLoopback.alcRenderSamplesSOFT(device, buffer, samples);
        buffer.position(0);
        byte[] pcm = new byte[samples * channels * 2];
        buffer.get(pcm);
        return pcm;
    }

    /** 设置 listener 位姿（位置/朝向）。 */
    public void setListener(AudioListenerPose pose) {
        setListener((float) pose.x(), (float) pose.y(), (float) pose.z());
        Quaternionf rotation = new Quaternionf().rotationYXZ(
                (float) Math.toRadians(-pose.yawDegrees()),
                (float) Math.toRadians(pose.pitchDegrees()),
                (float) Math.toRadians(pose.rollDegrees()));
        Vector3f forward = new Vector3f(0.0F, 0.0F, 1.0F).rotate(rotation);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(rotation);
        AL10.alListenerfv(AL10.AL_ORIENTATION,
                new float[] {forward.x, forward.y, forward.z, up.x, up.y, up.z});
    }

    /** 设置 listener 位置（朝向默认 +Z）。 */
    public void setListener(float x, float y, float z) {
        AL10.alListener3f(AL10.AL_POSITION, x, y, z);
        AL10.alListener3f(AL10.AL_VELOCITY, 0.0F, 0.0F, 0.0F);
        AL10.alListenerfv(AL10.AL_ORIENTATION, new float[] {0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 0.0F});
    }

    /** 释放测试源及其 buffer。 */
    public void destroySource(int source) {
        if (source == 0) {
            return;
        }
        int buffer = AL10.alGetSourcei(source, AL10.AL_BUFFER);
        AL10.alSourceStop(source);
        AL10.alDeleteSources(source);
        if (buffer != 0) {
            AL10.alDeleteBuffers(buffer);
        }
    }
}
