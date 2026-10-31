package com.captureapi.audio.timestamp;

/**
 * 音频块（I3.4.4，M4/M5 共同契约，冻结候选）。
 *
 * <p>字段全部 final；起始＝数据就绪时刻（CaptureClock），终点＝整数推算；{@code pcm} 写入后
 * 不得被调用方改写。本类是环形缓冲读端、M4 协议封装（I4.2.2）、M5 对齐规则（I5.1.2）的
 * 唯一输入形态——禁止并行定义。</p>
 *
 * @param startNanos  起始时间戳
 * @param endNanos    终点时间戳（起始 + 采样数×1e9/采样率）
 * @param sampleCount 实际采样数
 * @param pcm         定长 PCM 拍（PCM_SIGNED 16bit 小端交错）
 */
public record AudioChunk(long startNanos, long endNanos, int sampleCount, byte[] pcm) {

    public AudioChunk {
        if (startNanos < 0L || endNanos < startNanos) {
            throw new IllegalArgumentException("时间戳非法：start=" + startNanos + "，end=" + endNanos);
        }
        if (sampleCount <= 0) {
            throw new IllegalArgumentException("采样数必须为正：" + sampleCount);
        }
        if (pcm == null || pcm.length == 0) {
            throw new IllegalArgumentException("pcm 不可为空");
        }
    }

    /** @return 块时长（纳秒） */
    public long durationNanos() {
        return endNanos - startNanos;
    }
}
