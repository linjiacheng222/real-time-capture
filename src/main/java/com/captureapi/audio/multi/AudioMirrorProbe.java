package com.captureapi.audio.multi;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.mixin.SoundBufferAccessor;
import com.mojang.blaze3d.audio.SoundBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 0 镜像钩子探针：确认 Channel/SoundBuffer 访问器可用，不改变原音频。
 */
public final class AudioMirrorProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final boolean PROBE = Boolean.getBoolean(CaptureConstants.DEBUG_MULTILISTENER_PROBE_PROPERTY);
    private static final AtomicLong STATIC_BUFFERS = new AtomicLong();
    private static final AtomicLong STREAMS = new AtomicLong();
    private static final AtomicLong PLAYS = new AtomicLong();
    private static final AtomicLong DESTROYS = new AtomicLong();

    private AudioMirrorProbe() {
        throw new AssertionError("no instances");
    }

    public static void onStaticBuffer(SoundBuffer buffer) {
        if (!PROBE || buffer == null) {
            return;
        }
        SoundBufferAccessor accessor = (SoundBufferAccessor) (Object) buffer;
        long count = STATIC_BUFFERS.incrementAndGet();
        if (count <= 3L) {
            LOGGER.info("{} [MULTILISTENER/PROBE] attachStaticBuffer #{}：format={}，bytes={}",
                    CaptureConstants.LOG_MARKER, count, accessor.captureapi$getFormat(),
                    accessor.captureapi$getData() == null ? -1 : accessor.captureapi$getData().remaining());
        }
    }

    public static void onStream() {
        if (!PROBE) {
            return;
        }
        long count = STREAMS.incrementAndGet();
        if (count <= 3L) {
            LOGGER.info("{} [MULTILISTENER/PROBE] attachBufferStream #{}", CaptureConstants.LOG_MARKER, count);
        }
    }

    public static void onPlay() {
        if (!PROBE) {
            return;
        }
        long count = PLAYS.incrementAndGet();
        if (count <= 3L) {
            LOGGER.info("{} [MULTILISTENER/PROBE] Channel.play #{}（source=AL 上下文内）",
                    CaptureConstants.LOG_MARKER, count);
        }
    }

    public static void onDestroy() {
        if (!PROBE) {
            return;
        }
        DESTROYS.incrementAndGet();
    }

    public static long staticBuffers() {
        return STATIC_BUFFERS.get();
    }

    public static long streams() {
        return STREAMS.get();
    }

    public static long plays() {
        return PLAYS.get();
    }

    public static long destroys() {
        return DESTROYS.get();
    }
}
