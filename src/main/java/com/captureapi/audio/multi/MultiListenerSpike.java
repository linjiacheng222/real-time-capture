package com.captureapi.audio.multi;

import com.captureapi.core.constant.CaptureConstants;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 0 多 listener 可行性 spike：同进程创建两个 loopback context，用相同声源、
 * 不同 listener 渲染，验证输出独立且非静音。
 *
 * <p>由 dev JVM 属性 {@code captureapi.debug.multilistener_spike=true} 触发；不进入生产
 * 音频链路，不播放到扬声器。</p>
 */
public final class MultiListenerSpike {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private MultiListenerSpike() {
        throw new AssertionError("no instances");
    }

    /** 运行一次 spike；成功返回 true，失败返回 false（不抛到调用方）。 */
    public static boolean run() {
        LOGGER.info("{} [MULTILISTENER/SPIKE] 开始：双 loopback context + 同声源不同 listener",
                CaptureConstants.LOG_MARKER);
        LoopbackAudioContext a = new LoopbackAudioContext(CaptureConstants.MULTILISTENER_SPIKE_SAMPLE_RATE,
                CaptureConstants.MULTILISTENER_SPIKE_CHANNELS);
        LoopbackAudioContext b = new LoopbackAudioContext(CaptureConstants.MULTILISTENER_SPIKE_SAMPLE_RATE,
                CaptureConstants.MULTILISTENER_SPIKE_CHANNELS);
        int sourceA = 0;
        int sourceB = 0;
        try {
            if (!a.open()) {
                LOGGER.error("{} [MULTILISTENER/SPIKE] FAIL：context A 打开失败", CaptureConstants.LOG_MARKER);
                return false;
            }
            sourceA = a.createSineSource(CaptureConstants.MULTILISTENER_SPIKE_FREQUENCY,
                    CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X);
            a.setListener(0.0F, 0.0F, 0.0F);
            byte[] pcmA = a.render(CaptureConstants.MULTILISTENER_SPIKE_SAMPLES);
            a.destroySource(sourceA);
            sourceA = 0;
            a.close();

            if (!b.open()) {
                LOGGER.error("{} [MULTILISTENER/SPIKE] FAIL：context B 打开失败", CaptureConstants.LOG_MARKER);
                return false;
            }
            sourceB = b.createSineSource(CaptureConstants.MULTILISTENER_SPIKE_FREQUENCY,
                    CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X);
            b.setListener(CaptureConstants.MULTILISTENER_SPIKE_SOURCE_X, 0.0F, 0.0F);
            byte[] pcmB = b.render(CaptureConstants.MULTILISTENER_SPIKE_SAMPLES);
            b.destroySource(sourceB);
            sourceB = 0;
            b.close();

            boolean nonSilentA = rms(pcmA) > 0.0D;
            boolean nonSilentB = rms(pcmB) > 0.0D;
            boolean different = !Arrays.equals(pcmA, pcmB);
            LOGGER.info("{} [MULTILISTENER/SPIKE] A={} samples, B={} samples, rmsA={}, rmsB={}, "
                            + "nonSilentA={}, nonSilentB={}, different={}",
                    CaptureConstants.LOG_MARKER, pcmA.length, pcmB.length, rms(pcmA), rms(pcmB),
                    nonSilentA, nonSilentB, different);
            if (nonSilentA && nonSilentB && different) {
                LOGGER.info("{} [MULTILISTENER/SPIKE] PASS：同进程双 loopback context 可产生独立 PCM",
                        CaptureConstants.LOG_MARKER);
                return true;
            }
            LOGGER.error("{} [MULTILISTENER/SPIKE] FAIL：输出不满足非静音/独立条件",
                    CaptureConstants.LOG_MARKER);
            return false;
        } catch (Throwable t) {
            LOGGER.error("{} [MULTILISTENER/SPIKE] FAIL：{}", CaptureConstants.LOG_MARKER, t.toString());
            return false;
        } finally {
            try {
                if (sourceA != 0) {
                    a.destroySource(sourceA);
                }
            } catch (Throwable ignored) {
                // 清理失败不覆盖 spike 结果
            }
            try {
                if (sourceB != 0) {
                    b.destroySource(sourceB);
                }
            } catch (Throwable ignored) {
                // 清理失败不覆盖 spike 结果
            }
            try {
                a.close();
            } catch (Throwable ignored) {
                // 清理失败不覆盖 spike 结果
            }
            try {
                b.close();
            } catch (Throwable ignored) {
                // 清理失败不覆盖 spike 结果
            }
        }
    }

    private static double rms(byte[] pcm) {
        if (pcm == null || pcm.length < 2) {
            return 0.0D;
        }
        double sum = 0.0D;
        int count = pcm.length / 2;
        for (int i = 0; i < count; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            short sample = (short) ((hi << 8) | lo);
            double normalized = sample / (double) Short.MAX_VALUE;
            sum += normalized * normalized;
        }
        return Math.sqrt(sum / count);
    }
}
