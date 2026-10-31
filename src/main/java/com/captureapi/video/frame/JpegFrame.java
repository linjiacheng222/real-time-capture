package com.captureapi.video.frame;

/**
 * 编码后的 JPEG 帧（I2.5.1 / I2.5.2）。
 *
 * <p>发布到 {@link VideoFrameBus} 的不可变对象：JPEG 字节（内存流转，无文件落盘）、宽高、
 * 时间戳（取自 {@link RawFrame#timestampNanos()}，单调递增）。M4 出口按时间戳判断新鲜度。</p>
 *
 * @param jpeg           JPEG 字节
 * @param width          宽
 * @param height         高
 * @param timestampNanos 回读完成时刻（CaptureClock）
 */
public record JpegFrame(byte[] jpeg, int width, int height, long timestampNanos) {

    public JpegFrame {
        if (jpeg == null || jpeg.length == 0) {
            throw new IllegalArgumentException("jpeg 字节不可为空");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("帧尺寸非法：" + width + "x" + height);
        }
    }
}
