package com.captureapi.net.protocol;

import com.captureapi.audio.timestamp.AudioChunk;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 音频块线格式（I4.2.2）：块头 20B 小端（start long 8 + end long 8 + sampleCount int 4）+ PCM 载荷。
 *
 * <p>字段与 {@link AudioChunk} 一一对应；契约变更须先改 I3.4.4 内存结构。</p>
 */
public final class AudioChunkCodec {

    /** 音频块头字节数。 */
    public static final int HEADER_BYTES = 20;

    private AudioChunkCodec() {
        throw new AssertionError("no instances");
    }

    /**
     * 编码音频块。
     *
     * @param chunk 音频块
     * @return 线格式字节
     */
    public static byte[] encode(AudioChunk chunk) {
        byte[] pcm = chunk.pcm();
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(chunk.startNanos());
        buffer.putLong(chunk.endNanos());
        buffer.putInt(chunk.sampleCount());
        buffer.put(pcm);
        return buffer.array();
    }

    /**
     * 解码（接收端/测试用）。
     *
     * @param data 线格式字节
     * @return 音频块
     */
    public static AudioChunk decode(byte[] data) {
        if (data == null || data.length < HEADER_BYTES) {
            throw new IllegalArgumentException("音频消息过短");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        long start = buffer.getLong();
        long end = buffer.getLong();
        int sampleCount = buffer.getInt();
        byte[] pcm = new byte[data.length - HEADER_BYTES];
        buffer.get(pcm);
        return new AudioChunk(start, end, sampleCount, pcm);
    }
}
