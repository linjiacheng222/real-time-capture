package com.captureapi.video.frame;

/**
 * 离屏回读的原始帧（I2.4.2）。
 *
 * <p>像素为 RGBA8、行序为 OpenGL 自下而上（编码阶段逐行倒序写入 JPEG）。{@code rgba} 为渲染线程
 * 立即从 PBO 映射区拷贝出的堆内数组（拷贝后已 unmap），可安全交给编码/网络线程；约定只读。</p>
 *
 * @param rgba           RGBA8 像素数据（长度 = width×height×4）
 * @param width          宽
 * @param height         高
 * @param timestampNanos 回读完成时刻（CaptureClock.nowNanos）
 */
public record RawFrame(byte[] rgba, int width, int height, long timestampNanos) {

    public RawFrame {
        if (rgba == null) {
            throw new IllegalArgumentException("rgba 不可为 null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("帧尺寸非法：" + width + "x" + height);
        }
        long expected = (long) width * (long) height * 4L;
        if (rgba.length != expected) {
            throw new IllegalArgumentException("RGBA 长度不匹配：len=" + rgba.length + "，期望=" + expected);
        }
    }

    /** @return 每行字节数（stride，RGBA8 天然 4 字节对齐） */
    public int stride() {
        return width * 4;
    }

    /** @return 像素字节数（= rgba.length） */
    public int byteSize() {
        return rgba.length;
    }
}
