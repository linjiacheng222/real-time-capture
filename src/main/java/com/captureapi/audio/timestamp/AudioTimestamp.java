package com.captureapi.audio.timestamp;

/**
 * 音频块时间戳纯函数（I3.4.2）。
 *
 * <p>终点 = 起始 + 采样数 × 1e9 / 采样率（先乘后除，纯整数；默认 441×1e9/44100 = 10_000_000ns 恰为整）。
 * 与采集节拍公式同源，避免双时钟漂移（红线 4）。</p>
 */
public final class AudioTimestamp {

    private AudioTimestamp() {
        throw new AssertionError("no instances");
    }

    /**
     * 推算音频块终点。
     *
     * @param startNanos  起始时间戳（CaptureClock）
     * @param sampleCount 实际采样数
     * @param sampleRate  采样率
     * @return 终点时间戳
     */
    public static long endNanos(long startNanos, int sampleCount, int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate 必须为正：" + sampleRate);
        }
        return startNanos + (long) sampleCount * 1_000_000_000L / sampleRate;
    }
}
