package com.captureapi.net.protocol;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.frame.JpegFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 多路视频帧线格式（V2，WS binary type=0x04）。
 *
 * <pre>
 * [4B magic = 0x04 'C' 'A' 'P'][4B cameraId LE][8B timestamp][2B width][2B height][JPEG]
 * </pre>
 */
public final class MultiChannelVideoCodec {

    /** 4 字节魔数：0x04 + 'C' + 'A' + 'P'。 */
    public static final byte[] MAGIC = {0x04, 'C', 'A', 'P'};
    /** 22B 头。 */
    public static final int HEADER_BYTES = CaptureConstants.MULTI_VIDEO_CHANNEL_HEADER_BYTES;

    private MultiChannelVideoCodec() {
        throw new AssertionError("no instances");
    }

    public static byte[] encode(int cameraId, JpegFrame frame) {
        byte[] jpeg = frame.jpeg();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + jpeg.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(MAGIC);
        buffer.putInt(cameraId);
        buffer.putLong(frame.timestampNanos());
        buffer.putShort((short) frame.width());
        buffer.putShort((short) frame.height());
        buffer.putShort((short) 0);
        buffer.put(jpeg);
        return buffer.array();
    }

    public static Decoded decode(byte[] data) {
        if (data == null || data.length < HEADER_BYTES || !hasMagic(data)) {
            throw new IllegalArgumentException("多路视频消息非法");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(MAGIC.length);
        int cameraId = buffer.getInt();
        long timestamp = buffer.getLong();
        int width = buffer.getShort() & 0xFFFF;
        int height = buffer.getShort() & 0xFFFF;
        buffer.getShort();
        byte[] jpeg = new byte[data.length - HEADER_BYTES];
        buffer.get(jpeg);
        return new Decoded(cameraId, timestamp, width, height, jpeg);
    }

    /** 判断字节数组是否带多路视频 magic。 */
    public static boolean hasMagic(byte[] data) {
        if (data == null || data.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    public record Decoded(int cameraId, long timestampNanos, int width, int height, byte[] jpeg) {
    }
}
