package com.captureapi.net.protocol;

import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.core.constant.CaptureConstants;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 多通道音频块线格式（Phase 4，WS binary type=0x03）。
 *
 * <pre>
 * [1B type=0x03][4B channelId LE][8B start][8B end][4B sampleCount][PCM]
 * </pre>
 */
public final class MultiChannelAudioCodec {

    /** 消息类型值。 */
    public static final byte TYPE = 0x03;
    /** 头长度（type 1 + channelId 4 + start 8 + end 8 + sampleCount 4）。 */
    public static final int HEADER_BYTES = CaptureConstants.MULTI_AUDIO_CHANNEL_HEADER_BYTES;

    private MultiChannelAudioCodec() {
        throw new AssertionError("no instances");
    }

    public static byte[] encode(int channelId, AudioChunk chunk) {
        byte[] pcm = chunk.pcm();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(TYPE);
        buffer.putInt(channelId);
        buffer.putLong(chunk.startNanos());
        buffer.putLong(chunk.endNanos());
        buffer.putInt(chunk.sampleCount());
        buffer.put(pcm);
        return buffer.array();
    }

    public static Decoded decode(byte[] data) {
        if (data == null || data.length < HEADER_BYTES || data[0] != TYPE) {
            throw new IllegalArgumentException("多通道音频消息非法");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buffer.get();
        int channelId = buffer.getInt();
        long start = buffer.getLong();
        long end = buffer.getLong();
        int sampleCount = buffer.getInt();
        byte[] pcm = new byte[data.length - HEADER_BYTES];
        buffer.get(pcm);
        return new Decoded(channelId, new AudioChunk(start, end, sampleCount, pcm));
    }

    public record Decoded(int channelId, AudioChunk chunk) {
    }
}
