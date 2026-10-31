package com.captureapi.net.protocol;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.frame.JpegFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 视频帧线格式（I4.2.1，契约冻结候选）。
 *
 * <p>帧头 16B 小端：timestamp(long 8) + width(short 2) + height(short 2) + payloadLength(int 4)，
 * 随后为 JPEG 载荷。接收端以 payloadLength 自校验完整性。</p>
 */
public final class VideoFrameCodec {

    private VideoFrameCodec() {
        throw new AssertionError("no instances");
    }

    /**
     * 编码视频帧。
     *
     * @param frame JPEG 帧
     * @return 线格式字节
     */
    public static byte[] encode(JpegFrame frame) {
        byte[] jpeg = frame.jpeg();
        int header = CaptureConstants.VIDEO_FRAME_HEADER_BYTES;
        ByteBuffer buffer = ByteBuffer.allocate(header + jpeg.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(frame.timestampNanos());
        buffer.putShort((short) frame.width());
        buffer.putShort((short) frame.height());
        buffer.putInt(jpeg.length);
        buffer.put(jpeg);
        return buffer.array();
    }

    /**
     * 解码（接收端/测试用；长度不符抛 {@link IllegalArgumentException}）。
     *
     * @param data 线格式字节
     * @return 解码帧
     */
    public static DecodedVideoFrame decode(byte[] data) {
        if (data == null || data.length < CaptureConstants.VIDEO_FRAME_HEADER_BYTES) {
            throw new IllegalArgumentException("视频消息过短");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long timestamp = buffer.getLong();
        int width = buffer.getShort() & 0xFFFF;
        int height = buffer.getShort() & 0xFFFF;
        int payloadLength = buffer.getInt();
        if (payloadLength != data.length - CaptureConstants.VIDEO_FRAME_HEADER_BYTES) {
            throw new IllegalArgumentException("载荷长度不符：header=" + payloadLength
                    + "，actual=" + (data.length - CaptureConstants.VIDEO_FRAME_HEADER_BYTES));
        }
        byte[] jpeg = new byte[payloadLength];
        buffer.get(jpeg);
        return new DecodedVideoFrame(timestamp, width, height, jpeg);
    }

    /** 解码结果。 */
    public record DecodedVideoFrame(long timestampNanos, int width, int height, byte[] jpeg) {
    }
}
