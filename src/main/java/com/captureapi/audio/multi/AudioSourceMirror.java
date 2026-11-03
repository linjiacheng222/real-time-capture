package com.captureapi.audio.multi;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.mixin.SoundBufferAccessor;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft Channel → 多 listener context 的静态声源镜像（Phase 2）。
 *
 * <p>在 Channel.attachStaticBuffer 时复制 PCM/format，为每个活动 listener channel 创建一个
 * 独立 AL buffer/source；之后 play/position/pitch/volume/loop/stop/destroy 通过 worker
 * 命令队列广播。原 Channel/player 输出完全不改。</p>
 */
public final class AudioSourceMirror {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final Map<Channel, MirrorSet> SETS = new IdentityHashMap<>();
    private static final AtomicLong PLAY_COMMANDS = new AtomicLong();

    private AudioSourceMirror() {
        throw new AssertionError("no instances");
    }

    /** @return 是否启用镜像（feature 或 dev mirror self-test）。 */
    private static boolean active() {
        return AudioChannelRegistry.get().enabled()
                || Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_MIRROR_PROPERTY);
    }

    /** 静态 buffer 镜像。 */
    public static void onStaticBuffer(Channel channel, SoundBuffer buffer) {
        if (!active() || channel == null || buffer == null) {
            return;
        }
        SoundBufferAccessor accessor = (SoundBufferAccessor) (Object) buffer;
        AudioFormat format = accessor.captureapi$getFormat();
        ByteBuffer dataBuffer = accessor.captureapi$getData();
        if (format == null || dataBuffer == null) {
            return;
        }
        int alFormat = alFormat(format);
        if (alFormat == 0) {
            return;
        }
        int sampleRate = (int) format.getSampleRate();
        int peak = 0;
        if (LOGGER.isDebugEnabled()) {
            for (int i = 0; i + 1 < dataBuffer.remaining(); i += 2) {
                int value = Math.abs((short) ((dataBuffer.get(dataBuffer.position() + i + 1) & 0xFF) << 8
                        | (dataBuffer.get(dataBuffer.position() + i) & 0xFF)));
                peak = Math.max(peak, value);
            }
        }
        int length = dataBuffer.remaining();
        if (length <= 0) {
            return;
        }
        byte[] pcm = new byte[length];
        ByteBuffer copy = dataBuffer.duplicate();
        copy.get(pcm);
        MirrorSet set;
        synchronized (SETS) {
            set = SETS.computeIfAbsent(channel, key -> new MirrorSet());
        }
        List<AudioListenerChannel> channels = AudioChannelRegistry.get().mirrorChannels();
        LOGGER.debug("{} [AUDIO/MIRROR] static channel={} listeners={} format={} bytes={} peak={}",
                CaptureConstants.LOG_MARKER, System.identityHashCode(channel), channels.size(),
                format, length, peak);
        if (channels.isEmpty()) {
            return;
        }
        for (AudioListenerChannel listener : channels) {
            Handle handle;
            synchronized (set) {
                handle = set.handles.computeIfAbsent(listener.id(), id -> new Handle(listener));
            }
            listener.worker().submit(context -> {
                handle.release(context);
                int bufferId = context.createBuffer(pcm, alFormat, sampleRate);
                int sourceId = context.createSource();
                context.sourceBuffer(sourceId, bufferId);
                handle.buffer = bufferId;
                handle.source = sourceId;
                LOGGER.debug("{} [AUDIO/MIRROR] after-buffer ch={} err=0x{}",
                        CaptureConstants.LOG_MARKER, listener.id(),
                        Integer.toHexString(context.alError()));
                LOGGER.debug("{} [AUDIO/MIRROR] create-command ch={} source={} buffer={}",
                        CaptureConstants.LOG_MARKER, listener.id(), sourceId, bufferId);
            });
        }
    }

    /** Channel play 广播。 */
    public static void onPlay(Channel channel) {
        if (!active()) {
            return;
        }
        LOGGER.debug("{} [AUDIO/MIRROR] play channel={}", CaptureConstants.LOG_MARKER,
                System.identityHashCode(channel));
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            LOGGER.debug("{} [AUDIO/MIRROR] play-command ch={} source={}",
                    CaptureConstants.LOG_MARKER, listener.id(), handle.source);
            if (handle.source != 0) {
                context.sourcePlay(handle.source);
                LOGGER.debug("{} [AUDIO/MIRROR] after-play ch={} state=0x{} err=0x{}",
                        CaptureConstants.LOG_MARKER, listener.id(),
                        Integer.toHexString(context.sourceState(handle.source)),
                        Integer.toHexString(context.alError()));
                PLAY_COMMANDS.incrementAndGet();
            }
        }));
    }

    /** @return 已执行的镜像 play 命令数（dev smoke）。 */
    public static long playCommands() {
        return PLAY_COMMANDS.get();
    }

    /** 重置镜像 play 计数（dev smoke）。 */
    public static void resetPlayCommands() {
        PLAY_COMMANDS.set(0L);
    }

    /** Channel pause 广播。 */
    public static void onPause(Channel channel) {
        if (!active()) {
            return;
        }
        if (Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_MIRROR_PROPERTY)) {
            // 自动化测试窗口可能失焦触发全局 pause；self-test 下跳过 pause 以验证 buffer/play。
            LOGGER.debug("{} [AUDIO/MIRROR] self-test 跳过 pause", CaptureConstants.LOG_MARKER);
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourcePause(handle.source);
            }
        }));
    }

    /** Channel unpause 广播。 */
    public static void onUnpause(Channel channel) {
        if (!active()) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourcePlay(handle.source);
            }
        }));
    }

    /** Channel stop 广播。 */
    public static void onStop(Channel channel) {
        if (!active()) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourceStop(handle.source);
            }
        }));
    }

    /** 位置同步。 */
    public static void onPosition(Channel channel, Vec3 position) {
        if (!active() || position == null) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourcePosition(handle.source, position.x, position.y, position.z);
            }
        }));
    }

    public static void onPitch(Channel channel, float pitch) {
        if (!active()) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourcePitch(handle.source, pitch);
            }
        }));
    }

    public static void onVolume(Channel channel, float volume) {
        if (!active()) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourceVolume(handle.source, volume);
            }
        }));
    }

    public static void onLooping(Channel channel, boolean looping) {
        if (!active()) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourceLooping(handle.source, looping);
            }
        }));
    }

    public static void onDisableAttenuation(Channel channel) {
        if (!active()) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourceDisableAttenuation(handle.source);
            }
        }));
    }

    public static void onLinearAttenuation(Channel channel, float factor) {
        if (!active()) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourceLinearAttenuation(handle.source, factor);
            }
        }));
    }

    public static void onRelative(Channel channel, boolean relative) {
        if (!active()) {
            return;
        }
        forEachHandle(channel, (listener, handle) -> listener.worker().submit(context -> {
            if (handle.source != 0) {
                context.sourceRelative(handle.source, relative);
            }
        }));
    }

    /** Channel 销毁：释放所有镜像 source/buffer。 */
    public static void onDestroy(Channel channel) {
        if (channel == null) {
            return;
        }
        MirrorSet set;
        synchronized (SETS) {
            set = SETS.remove(channel);
        }
        if (set == null) {
            return;
        }
        List<Handle> handles;
        synchronized (set) {
            handles = new ArrayList<>(set.handles.values());
            set.handles.clear();
        }
        for (Handle handle : handles) {
            handle.listener.worker().submit(context -> handle.release(context));
        }
    }

    /** @return 当前镜像 Channel 数（诊断）。 */
    public static int mirroredChannelCount() {
        synchronized (SETS) {
            return SETS.size();
        }
    }

    private static void forEachHandle(Channel channel, HandleConsumer consumer) {
        if (channel == null) {
            return;
        }
        MirrorSet set;
        synchronized (SETS) {
            set = SETS.get(channel);
        }
        if (set == null) {
            return;
        }
        List<Map.Entry<Integer, Handle>> entries;
        synchronized (set) {
            entries = new ArrayList<>(set.handles.entrySet());
        }
        for (Map.Entry<Integer, Handle> entry : entries) {
            consumer.accept(entry.getValue().listener, entry.getValue());
        }
    }

    private static int alFormat(AudioFormat format) {
        if (format.getSampleSizeInBits() != 16) {
            return 0;
        }
        if (format.getChannels() == 1) {
            return AL10.AL_FORMAT_MONO16;
        }
        if (format.getChannels() == 2) {
            return AL10.AL_FORMAT_STEREO16;
        }
        return 0;
    }

    @FunctionalInterface
    private interface HandleConsumer {
        void accept(AudioListenerChannel listener, Handle handle);
    }

    private static final class MirrorSet {
        private final Map<Integer, Handle> handles = new HashMap<>();
    }

    private static final class Handle {
        private final AudioListenerChannel listener;
        private int source;
        private int buffer;

        private Handle(AudioListenerChannel listener) {
            this.listener = listener;
        }

        private void release(LoopbackAudioContext context) {
            if (source != 0) {
                context.deleteSource(source);
                source = 0;
            }
            if (buffer != 0) {
                context.deleteBuffer(buffer);
                buffer = 0;
            }
        }
    }
}
