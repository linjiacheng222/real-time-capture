package com.captureapi.audio.multi;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/**
 * 流式声音 tee：原 reader 读到的 PCM 复制给镜像层，原 reader 行为不变。
 */
public final class TeeAudioStream implements AudioStream {

    private final AudioStream delegate;
    private final AudioSourceMirror.StreamSink sink;
    private final AudioFormat format;

    public TeeAudioStream(AudioStream delegate, AudioSourceMirror.StreamSink sink) {
        this.delegate = delegate;
        this.sink = sink;
        this.format = delegate.getFormat();
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        ByteBuffer data = delegate.read(size);
        if (data != null && data.remaining() > 0) {
            ByteBuffer copy = data.duplicate();
            byte[] pcm = new byte[copy.remaining()];
            copy.get(pcm);
            sink.onPcm(pcm);
        }
        return data;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
