package com.captureapi.audio.multi;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.time.CaptureClock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个 listener channel 的 loopback 渲染 worker（Phase 1）。
 *
 * <p>每个 worker 自己创建 device/context、拥有独立 listener 与 PCM ring；只有本线程
 * 执行 AL 调用。Phase 2 起由 {@link AudioSourceMirror} 向本 worker 投递镜像命令。</p>
 */
public final class AudioContextWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private final String name;
    private final AudioPcmRing ring;
    private final int sampleRate;
    private final int channels;
    private final int pollIntervalMs;
    private final int samplesPerSlot;
    private final long pollNanos;

    private volatile AudioListenerPose pose = new AudioListenerPose(0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F);
    private volatile boolean running;
    private volatile Throwable lastError;
    private final AtomicLong renderedPolls = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private Thread thread;
    private LoopbackAudioContext context;

    public AudioContextWorker(String name, AudioPcmRing ring, int sampleRate, int channels,
                              int pollIntervalMs) {
        this.name = name;
        this.ring = ring;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.pollIntervalMs = pollIntervalMs;
        this.samplesPerSlot = Math.max(1, sampleRate * pollIntervalMs / 1000);
        this.pollNanos = Math.max(1L, pollIntervalMs) * 1_000_000L;
    }

    /**
     * 启动 worker。
     *
     * @param testFrequency 可选合成测试源频率；<=0 表示不创建
     * @param testX         测试源 X
     * @return 是否成功启动
     */
    public synchronized boolean start(double testFrequency, double testX) {
        if (running) {
            return true;
        }
        context = new LoopbackAudioContext(sampleRate, channels);
        if (!context.open()) {
            lastError = new IllegalStateException("loopback context open 失败");
            errors.incrementAndGet();
            return false;
        }
        if (testFrequency > 0.0D) {
            try {
                context.createSineSource((float) testFrequency, (float) testX);
            } catch (Throwable t) {
                lastError = t;
                errors.incrementAndGet();
                context.close();
                return false;
            }
        }
        running = true;
        thread = new Thread(this::run, name);
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                Math.min(Thread.MAX_PRIORITY - 1, CaptureConstants.AUDIO_CHANNEL_THREAD_PRIORITY)));
        thread.start();
        return true;
    }

    /** 设置 listener 位姿（下一拍生效）。 */
    public void setListenerPose(AudioListenerPose pose) {
        if (pose != null) {
            this.pose = pose;
        }
    }

    private void run() {
        long next = CaptureClock.get().nowNanos();
        try {
            while (running) {
                AudioListenerPose current = pose;
                context.setListener(current);
                byte[] pcm = context.render(samplesPerSlot);
                long start = CaptureClock.get().nowNanos();
                ring.write(pcm, start, ring.endNanosFor(start, samplesPerSlot), samplesPerSlot);
                renderedPolls.incrementAndGet();
                next += pollNanos;
                long sleep = next - CaptureClock.get().nowNanos();
                if (sleep > 0L) {
                    LockSupport.parkNanos(Math.min(sleep, pollNanos));
                } else {
                    next = CaptureClock.get().nowNanos();
                }
                if (Thread.interrupted()) {
                    break;
                }
            }
        } catch (Throwable t) {
            lastError = t;
            errors.incrementAndGet();
            LOGGER.warn("{} [AUDIO/CH] worker {} 异常退出：{}", CaptureConstants.LOG_MARKER, name, t.toString());
        } finally {
            if (context != null) {
                context.close();
            }
            running = false;
        }
    }

    /** 停止并等待 worker/context 回收。 */
    public synchronized void stop() {
        running = false;
        Thread current = thread;
        thread = null;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
            try {
                current.join(CaptureConstants.AUDIO_CHANNEL_STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long renderedPolls() {
        return renderedPolls.get();
    }

    public long errors() {
        return errors.get();
    }

    public Throwable lastError() {
        return lastError;
    }

    public AudioPcmRing ring() {
        return ring;
    }
}
