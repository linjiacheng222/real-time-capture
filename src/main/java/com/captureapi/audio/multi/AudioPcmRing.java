package com.captureapi.audio.multi;

import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.audio.timestamp.AudioTimestamp;
import java.util.concurrent.locks.LockSupport;
import com.captureapi.core.time.CaptureClock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每 listener 通道的 PCM 环形缓冲（Phase 1）。
 *
 * <p>与旧 {@code AudioRingBuffer} 同构的定长槽环，但可实例化；单写（context worker）/
 * 多读快照。容量/格式来自全局音频配置。</p>
 */
public final class AudioPcmRing {

    private final int sampleRate;
    private final int channels;
    private final int samplesPerSlot;
    private final int slotBytes;
    private final int slotCount;
    private final byte[] slots;
    private final long[] slotStartNanos;
    private final long[] slotEndNanos;
    private final int[] slotSampleCounts;

    private int writeIndex;
    private int readIndex;
    private volatile boolean sealed;
    private volatile AudioChunk lastWritten;
    private final AtomicLong writtenPolls = new AtomicLong();
    private final AtomicLong droppedPolls = new AtomicLong();

    public AudioPcmRing(int sampleRate, int channels, int pollIntervalMs, double capacitySec) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.samplesPerSlot = Math.max(1, sampleRate * pollIntervalMs / 1000);
        this.slotBytes = samplesPerSlot * channels * 2;
        int pollsPerSecond = Math.max(1, 1000 / Math.max(1, pollIntervalMs));
        this.slotCount = Math.max(2, (int) Math.round(pollsPerSecond * capacitySec));
        this.slots = new byte[slotCount * slotBytes];
        this.slotStartNanos = new long[slotCount];
        this.slotEndNanos = new long[slotCount];
        this.slotSampleCounts = new int[slotCount];
    }

    /** 写一拍（worker 唯一写端）。 */
    public synchronized boolean write(byte[] pcm, long startNanos, long endNanos, int sampleCount) {
        if (sealed || pcm == null || pcm.length != slotBytes) {
            return false;
        }
        int write = writeIndex;
        int read = readIndex;
        int occupancy = (write - read + slotCount) % slotCount;
        if (occupancy == slotCount) {
            readIndex = (read + 1) % slotCount;
            droppedPolls.incrementAndGet();
        }
        System.arraycopy(pcm, 0, slots, write * slotBytes, slotBytes);
        slotStartNanos[write] = startNanos;
        slotEndNanos[write] = endNanos;
        slotSampleCounts[write] = sampleCount;
        writeIndex = (write + 1) % slotCount;
        lastWritten = new AudioChunk(startNanos, endNanos, sampleCount, pcm.clone());
        writtenPolls.incrementAndGet();
        return true;
    }

    /** 取最近 n 拍合并为一个 AudioChunk；不足/封口无数据返回 null。 */
    public synchronized AudioChunk snapshotRecent(int count) {
        if (sealed || count <= 0) {
            return null;
        }
        int available = (writeIndex - readIndex + slotCount) % slotCount;
        int n = Math.min(count, available);
        if (n <= 0) {
            return null;
        }
        int startIndex = (writeIndex - n + slotCount) % slotCount;
        byte[] pcm = new byte[n * slotBytes];
        long start = slotStartNanos[startIndex];
        long end = slotEndNanos[startIndex];
        int sampleCount = 0;
        for (int i = 0; i < n; i++) {
            int index = (startIndex + i) % slotCount;
            System.arraycopy(slots, index * slotBytes, pcm, i * slotBytes, slotBytes);
            end = slotEndNanos[index];
            sampleCount += slotSampleCounts[index];
        }
        return new AudioChunk(start, end, sampleCount, pcm);
    }

    /**
     * 阻塞读取下一拍（主出口消费；超时返回 null）。
     */
    public AudioChunk readChunk(long timeoutMillis) {
        long deadline = CaptureClock.get().nowNanos()
                + Math.max(0L, timeoutMillis) * com.captureapi.core.constant.CaptureConstants.NANOS_PER_MILLI;
        while (true) {
            int read;
            int write;
            synchronized (this) {
                read = readIndex;
                write = writeIndex;
                if (read != write) {
                    byte[] pcm = new byte[slotBytes];
                    System.arraycopy(slots, read * slotBytes, pcm, 0, slotBytes);
                    AudioChunk chunk = new AudioChunk(slotStartNanos[read], slotEndNanos[read],
                            slotSampleCounts[read], pcm);
                    readIndex = (read + 1) % slotCount;
                    return chunk;
                }
            }
            long remaining = deadline - CaptureClock.get().nowNanos();
            if (remaining <= 0L) {
                return null;
            }
            LockSupport.parkNanos(this, Math.min(remaining, 1_000_000L));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** @return 最新一拍，无数据 null。 */
    public AudioChunk peekLatest() {
        return lastWritten;
    }

    /** 写端口封口；之后写失败、快照返回 null。 */
    public synchronized void seal() {
        sealed = true;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int channels() {
        return channels;
    }

    public int samplesPerSlot() {
        return samplesPerSlot;
    }

    public int slotBytes() {
        return slotBytes;
    }

    public long writtenPolls() {
        return writtenPolls.get();
    }

    public long droppedPolls() {
        return droppedPolls.get();
    }

    /** 写入时使用的整数时间戳辅助。 */
    public long endNanosFor(long startNanos, int samples) {
        return AudioTimestamp.endNanos(startNanos, samples, sampleRate);
    }

    /** 当前时间戳（CaptureClock，唯一时钟源）。 */
    public long nowNanos() {
        return CaptureClock.get().nowNanos();
    }
}
