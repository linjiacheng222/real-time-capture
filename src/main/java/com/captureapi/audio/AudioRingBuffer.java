package com.captureapi.audio;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.ManagedResource;
import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.audio.timestamp.AudioTimestamp;
import com.captureapi.core.time.CaptureClock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 环形音频缓冲区（T3.2 全链路）。
 *
 * <p>单写（采集线程）/单读（M4 消费端）定长字节环，预分配零运行期分配：每槽一个标准 PCM 拍
 * （默认 441×2×2=1764B），槽数 = 每秒拍数×{@code ring_capacity_sec}（默认 100 槽≈176.4KB）。</p>
 *
 * <p>写端永不阻塞：满时覆盖最旧拍并计数（I3.2.4）；读端有界等待，超时返回 TIMEOUT，
 * 由消费端用 {@link #readOrSilence(long)} 补零（I3.2.5）；关闭序列置 EOF，读端排空后收到 EOF。
 * 全局级资源（跨世界存续），经 {@link com.captureapi.core.resource.WorldResourceManager} 登记。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class AudioRingBuffer implements ManagedResource {

    /** 资源键（T3.2.6 固定键）。 */
    public static final String KEY = CaptureConstants.RESOURCE_KEY_PREFIX + "audio:ring_buffer";

    public enum ReadStatus {
        /** 读到一拍 */
        DATA,
        /** 超时（调用方可用 readOrSilence 补静音） */
        TIMEOUT,
        /** 已封口且已排空 */
        EOF
    }

    /**
     * 读结果（不可变）。
     *
     * @param status 状态
     * @param pcm    数据拍（DATA 时非空；TIMEOUT/EOF 时 null）
     */
    public record ReadResult(ReadStatus status, byte[] pcm) {
        public static ReadResult data(byte[] pcm) {
            return new ReadResult(ReadStatus.DATA, pcm);
        }

        public static ReadResult timeout() {
            return new ReadResult(ReadStatus.TIMEOUT, null);
        }

        public static ReadResult eof() {
            return new ReadResult(ReadStatus.EOF, null);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final AudioRingBuffer INSTANCE = new AudioRingBuffer();

    private volatile byte[] slots;
    private long[] slotStartNanos;
    private long[] slotEndNanos;
    private int[] slotSampleCounts;
    private int slotCount;
    private int slotBytes;
    private int samplesPerSlot;
    private int sampleRate;
    private volatile long lastReadEndNanos;
    private volatile int writeIndex;
    private volatile int readIndex;
    private volatile boolean sealed;
    private volatile byte[] silenceFrame;
    private volatile AudioChunk lastWritten;

    private final AtomicLong writtenPolls = new AtomicLong();
    private final AtomicLong droppedPolls = new AtomicLong();
    private final AtomicLong silentPolls = new AtomicLong();
    private final AtomicLong invalidPolls = new AtomicLong();

    private long lastWatermarkNanos;
    private long lastDroppedSnapshot;
    private long lastSilentSnapshot;

    private AudioRingBuffer() {
    }

    public static AudioRingBuffer get() {
        return INSTANCE;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public void create() {
        if (slots != null) {
            return;
        }
        int sampleRate = CaptureParams.get().sampleRate();
        int channels = CaptureParams.get().channels();
        int pollIntervalMs = CaptureParams.get().pollIntervalMs();
        int samplesPerPoll = Math.max(1, sampleRate * pollIntervalMs / 1000);
        slotBytes = samplesPerPoll * channels * 2;
        int pollsPerSecond = Math.max(1, 1000 / Math.max(1, pollIntervalMs));
        slotCount = Math.max(2, (int) Math.round(pollsPerSecond * CaptureParams.get().ringCapacitySec()));
        slots = new byte[slotCount * slotBytes];
        slotStartNanos = new long[slotCount];
        slotEndNanos = new long[slotCount];
        slotSampleCounts = new int[slotCount];
        samplesPerSlot = samplesPerPoll;
        this.sampleRate = sampleRate;
        silenceFrame = new byte[slotBytes];
        writeIndex = 0;
        readIndex = 0;
        sealed = false;
        lastWatermarkNanos = CaptureClock.get().nowNanos();
        LOGGER.info("{} 环形音频缓冲创建：{} 槽 × {} bytes（{} 秒容量，{} 拍/秒）",
                CaptureConstants.LOG_MARKER, slotCount, slotBytes,
                String.format(java.util.Locale.ROOT, "%.2f", CaptureParams.get().ringCapacitySec()),
                pollsPerSecond);
    }

    @Override
    public void destroy() {
        seal();
        slots = null;
        lastWritten = null;
        slotStartNanos = null;
        slotEndNanos = null;
        slotSampleCounts = null;
        silenceFrame = null;
        LOGGER.info("{} 环形音频缓冲释放：写入={}, 丢拍={}, 静音={}, 非法={}", CaptureConstants.LOG_MARKER,
                writtenPolls.get(), droppedPolls.get(), silentPolls.get(), invalidPolls.get());
    }

    /**
     * 写一拍（采集线程唯一写端；永不阻塞）。
     *
     * @param pcm         定长 PCM 拍
     * @param startNanos  起始时间戳（CaptureClock，I3.4.1）
     * @param endNanos    终点时间戳（整数推算，I3.4.2）
     * @param sampleCount 实际采样数
     * @return 是否写入（封口/短拍/未创建时 false）
     */
    public synchronized boolean write(byte[] pcm, long startNanos, long endNanos, int sampleCount) {
        byte[] ring = slots;
        if (ring == null || sealed) {
            return false;
        }
        if (pcm == null || pcm.length != slotBytes) {
            invalidPolls.incrementAndGet();
            if (invalidPolls.get() % 100L == 1L) {
                LOGGER.warn("{} 环形缓冲收到非法拍长：{}，期望 {}", CaptureConstants.LOG_MARKER,
                        pcm == null ? -1 : pcm.length, slotBytes);
            }
            return false;
        }
        int write = writeIndex;
        int read = readIndex;
        int occupancy = (write - read + slotCount) % slotCount;
        if (occupancy == slotCount) {
            // 满：丢最旧、写最新（写端零等待）
            readIndex = (read + 1) % slotCount;
            droppedPolls.incrementAndGet();
        }
        System.arraycopy(pcm, 0, ring, write * slotBytes, slotBytes);
        slotStartNanos[write] = startNanos;
        slotEndNanos[write] = endNanos;
        slotSampleCounts[write] = sampleCount;
        writeIndex = (write + 1) % slotCount;
        lastWritten = new AudioChunk(startNanos, endNanos, sampleCount, pcm);
        writtenPolls.incrementAndGet();
        LockSupport.unpark(getWaitThread());
        maybeLogWatermark();
        return true;
    }

    /**
     * 读一拍（M4 消费端唯一读端；有界等待，可中断）。
     *
     * @param timeoutMillis 超时毫秒（0=立即返回）
     * @return DATA/TIMEOUT/EOF
     */
    public ReadResult read(long timeoutMillis) {
        ensureCreated();
        long deadline = CaptureClock.get().nowNanos() + Math.max(0L, timeoutMillis) * 1_000_000L;
        while (true) {
            int read = readIndex;
            int write = writeIndex;
            if (read != write) {
                byte[] pcm = new byte[slotBytes];
                System.arraycopy(slots, read * slotBytes, pcm, 0, slotBytes);
                readIndex = (read + 1) % slotCount;
                return ReadResult.data(pcm);
            }
            if (sealed) {
                return ReadResult.eof();
            }
            long remaining = deadline - CaptureClock.get().nowNanos();
            if (remaining <= 0L) {
                return ReadResult.timeout();
            }
            LockSupport.parkNanos(this, Math.min(remaining, 1_000_000L));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return ReadResult.timeout();
            }
        }
    }

    /**
     * 读一拍；超时补全零静音拍（I3.2.5）。EOF 返回 null。
     *
     * @param timeoutMillis 超时毫秒
     * @return PCM 拍（DATA 原数据；TIMEOUT 全零；EOF null）
     */
    public byte[] readOrSilence(long timeoutMillis) {
        ReadResult result = read(timeoutMillis);
        if (result.status() == ReadStatus.DATA) {
            return result.pcm();
        }
        if (result.status() == ReadStatus.EOF) {
            return null;
        }
        silentPolls.incrementAndGet();
        byte[] zero = silenceFrame;
        if (zero == null) {
            return new byte[slotBytes];
        }
        byte[] copy = new byte[zero.length];
        System.arraycopy(zero, 0, copy, 0, zero.length);
        return copy;
    }

    /**
     * 读一个带时间戳的音频块（M4/M5 消费端；有界等待）。
     *
     * @param timeoutMillis 超时毫秒
     * @return DATA 时 AudioChunk；TIMEOUT/EOF 时 null
     */
    public ReadChunkResult readChunk(long timeoutMillis) {
        ensureCreated();
        long deadline = CaptureClock.get().nowNanos() + Math.max(0L, timeoutMillis) * 1_000_000L;
        while (true) {
            int read = readIndex;
            int write = writeIndex;
            if (read != write) {
                byte[] pcm = new byte[slotBytes];
                System.arraycopy(slots, read * slotBytes, pcm, 0, slotBytes);
                AudioChunk chunk = new AudioChunk(slotStartNanos[read], slotEndNanos[read],
                        slotSampleCounts[read], pcm);
                lastReadEndNanos = chunk.endNanos();
                readIndex = (read + 1) % slotCount;
                return new ReadChunkResult(ReadStatus.DATA, chunk);
            }
            if (sealed) {
                return new ReadChunkResult(ReadStatus.EOF, null);
            }
            long remaining = deadline - CaptureClock.get().nowNanos();
            if (remaining <= 0L) {
                return new ReadChunkResult(ReadStatus.TIMEOUT, null);
            }
            LockSupport.parkNanos(this, Math.min(remaining, 1_000_000L));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return new ReadChunkResult(ReadStatus.TIMEOUT, null);
            }
        }
    }

    /**
     * 读一个块；超时补全零静音块（I3.2.5/I3.4.3）；EOF 返回 null。
     *
     * @param timeoutMillis 超时毫秒
     * @return AudioChunk（静音块时间戳按标称节奏推进）
     */
    public AudioChunk readChunkOrSilence(long timeoutMillis) {
        ReadChunkResult result = readChunk(timeoutMillis);
        if (result.status() == ReadStatus.DATA) {
            return result.chunk();
        }
        if (result.status() == ReadStatus.EOF) {
            return null;
        }
        silentPolls.incrementAndGet();
        long start = lastReadEndNanos != 0L ? lastReadEndNanos : CaptureClock.get().nowNanos();
        long end = AudioTimestamp.endNanos(start, samplesPerSlot, sampleRate);
        lastReadEndNanos = end;
        byte[] zero = new byte[slotBytes];
        return new AudioChunk(start, end, samplesPerSlot, zero);
    }

    /**
     * 非消费地查看最近写入的音频块（API 只读/诊断；并发写入时可能看到正在写入的槽，按快照近似）。
     *
     * @return 最近音频块；无数据 null
     */
    /**
     * 快照最近 N 拍（音频截面 W-071 用；不推进读游标，不干扰 WS/录像消费端）。
     *
     * @param chunkCount 拍数（1..ring 容量）
     * @return 合并后的 AudioChunk；无数据/未创建返回 null
     */
    public synchronized AudioChunk snapshotRecent(int chunkCount) {
        byte[] ring = slots;
        if (ring == null || chunkCount <= 0) {
            return null;
        }
        int write = writeIndex;
        // 音频截面需要最近 N 拍；WS 推送线程会持续消费 readIndex，因此不能只看可读队列。
        // 这里按写入位置回看最近 N 个物理槽（不超过已写拍数与环容量）；并发覆盖窗口由调用方接受。
        int count = Math.min(chunkCount, slotCount);
        count = Math.min(count, (int) Math.min(writtenPolls.get(), slotCount));
        if (count <= 0) {
            return null;
        }
        int startIndex = (write - count + slotCount) % slotCount;
        byte[] pcm = new byte[count * slotBytes];
        for (int i = 0; i < count; i++) {
            int index = (startIndex + i) % slotCount;
            System.arraycopy(ring, index * slotBytes, pcm, i * slotBytes, slotBytes);
        }
        int first = startIndex;
        int last = (startIndex + count - 1) % slotCount;
        int samples = 0;
        for (int i = 0; i < count; i++) {
            samples += slotSampleCounts[(startIndex + i) % slotCount];
        }
        return new AudioChunk(slotStartNanos[first], slotEndNanos[last], samples, pcm);
    }

    public AudioChunk peekLatest() {
        return lastWritten;
    }

    /**
     * 带时间戳读结果。
     *
     * @param status 状态
     * @param chunk  DATA 时的音频块
     */
    public record ReadChunkResult(ReadStatus status, AudioChunk chunk) {
    }

    /** 封口：写端停止后调用（I3.1.6 关闭序列）；读端排空后收到 EOF。 */
    public void seal() {
        sealed = true;
        LockSupport.unpark(getWaitThread());
    }

    /** @return 是否已封口 */
    public boolean isSealed() {
        return sealed;
    }

    /** @return 当前占用槽位 */
    public int occupancy() {
        if (slots == null) {
            return 0;
        }
        return (writeIndex - readIndex + slotCount) % slotCount;
    }

    /** @return 水位百分比 0~100 */
    public double watermarkPercent() {
        if (slots == null || slotCount == 0) {
            return 0.0D;
        }
        return occupancy() * 100.0D / slotCount;
    }

    public long writtenPolls() {
        return writtenPolls.get();
    }

    public long droppedPolls() {
        return droppedPolls.get();
    }

    public long silentPolls() {
        return silentPolls.get();
    }

    public long invalidPolls() {
        return invalidPolls.get();
    }

    public int slotCount() {
        return slotCount;
    }

    public int slotBytes() {
        return slotBytes;
    }

    private void ensureCreated() {
        if (slots == null) {
            create();
        }
    }

    private Thread waiter;

    private Thread getWaitThread() {
        return waiter;
    }

    /** 由消费线程注册等待者（可选优化；未注册时依靠 parkNanos 超时轮询）。 */
    public void registerReaderThread(Thread thread) {
        this.waiter = thread;
    }

    private void maybeLogWatermark() {
        if (!CaptureParams.get().logBufferWatermark()) {
            return;
        }
        long now = CaptureClock.get().nowNanos();
        long elapsed = now - lastWatermarkNanos;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        lastWatermarkNanos = now;
        long dropped = droppedPolls.get();
        long silent = silentPolls.get();
        long dropDelta = dropped - lastDroppedSnapshot;
        long silentDelta = silent - lastSilentSnapshot;
        lastDroppedSnapshot = dropped;
        lastSilentSnapshot = silent;
        LOGGER.info("{} 音频缓冲水位：{}%（{}/{}），丢拍={}（+{}），静音={}（+{}）",
                CaptureConstants.LOG_MARKER, String.format(java.util.Locale.ROOT, "%.1f", watermarkPercent()),
                occupancy(), slotCount, dropped, dropDelta, silent, silentDelta);
        if (dropDelta > 50L) {
            LOGGER.warn("{} 音频缓冲持续过载（>{} 拍/秒）：疑似消费端故障", CaptureConstants.LOG_MARKER, dropDelta);
        }
    }
}
