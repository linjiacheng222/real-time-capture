package com.captureapi.net.protocol;

import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.core.constant.CaptureConstants;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 多通道音频块线格式（Phase 4，WS binary type=0x03）。
 *
 * <pre>
 * [4B magic = 0x03 'C' 'A' 'P'][4B channelId LE][8B start][8B end][4B sampleCount][PCM]
 * </pre>
 *
 * <p>magic 用于与无 type 字节的旧 VideoFrameCodec/AudioChunkCodec 区分。</p>
 */
public final class MultiChannelAudioCodec {

    /** 4 字节魔数：0x03 + 'C' + 'A' + 'P'。 */
    public static final byte[] MAGIC = {0x03, 'C', 'A', 'P'};
    /** 头长度（magic 4 + channelId 4 + start 8 + end 8 + sampleCount 4）。 */
    public static final int HEADER_BYTES = CaptureConstants.MULTI_AUDIO_CHANNEL_HEADER_BYTES;

    private MultiChannelAudioCodec() {
        throw new AssertionError("no instances");
    }

    public static byte[] encode(int channelId, AudioChunk chunk) {
        byte[] pcm = chunk.pcm();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(MAGIC);
        buffer.putInt(channelId);
        buffer.putLong(chunk.startNanos());
        buffer.putLong(chunk.endNanos());
        buffer.putInt(chunk.sampleCount());
        buffer.put(pcm);
        return buffer.array();
    }

    public static Decoded decode(byte[] data) {
        if (data == null || data.length < HEADER_BYTES || !hasMagic(data)) {
            throw new IllegalArgumentException("多通道音频消息非法");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(MAGIC.length);
        int channelId = buffer.getInt();
        long start = buffer.getLong();
        long end = buffer.getLong();
        int sampleCount = buffer.getInt();
        byte[] pcm = new byte[data.length - HEADER_BYTES];
        buffer.get(pcm);
        return new Decoded(channelId, new AudioChunk(start, end, sampleCount, pcm));
    }

    /** 判断字节数组是否带多通道音频 magic。 */
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

    public record Decoded(int channelId, AudioChunk chunk) {
    }
}
