package com.captureapi.net;

import com.captureapi.audio.AudioRingBuffer;
import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.net.protocol.AudioChunkCodec;
import com.captureapi.net.protocol.VideoFrameCodec;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.frame.VideoFrameBus;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WS 音视频推流线程（I4.2.3）。
 *
 * <p>专用单线程 {@code captureapi-push}，数据驱动：音频从环形缓冲按拍读取（10ms 节奏），
 * 视频轮询最新帧总线（同一帧只推一次）；每连接经私有有界队列发送（I4.2.5），推送线程永不
 * 因慢连接阻塞。无连接时仍消费音频，避免重连后收到陈旧积压。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class StreamPushService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final StreamPushService INSTANCE = new StreamPushService();
    private static final String THREAD_NAME = "captureapi-push";
    private static final long AUDIO_READ_TIMEOUT_MS = 20L;

    private final AtomicLong videoFrames = new AtomicLong();
    private final AtomicLong audioChunks = new AtomicLong();

    private volatile boolean running;
    private Thread pushThread;
    private CaptureWebSocketServer server;

    private StreamPushService() {
    }

    public static StreamPushService get() {
        return INSTANCE;
    }

    /**
     * 启动推送线程（服务启动后调用；幂等）。
     *
     * @param webSocketServer WS 服务
     */
    public synchronized void start(CaptureWebSocketServer webSocketServer) {
        if (running || webSocketServer == null) {
            return;
        }
        server = webSocketServer;
        running = true;
        Thread thread = new Thread(this::pushLoop, THREAD_NAME);
        thread.setDaemon(true);
        pushThread = thread;
        thread.start();
        LOGGER.info("{} [NET/PUSH] 推流线程启动：{}", CaptureConstants.LOG_MARKER, THREAD_NAME);
    }

    /** 停止推送线程（幂等）。 */
    public synchronized void stop() {
        running = false;
        Thread thread = pushThread;
        pushThread = null;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        LOGGER.info("{} [NET/PUSH] 推流线程停止：video={}，audio={}", CaptureConstants.LOG_MARKER,
                videoFrames.get(), audioChunks.get());
    }

    private void pushLoop() {
        long lastVideoTimestamp = 0L;
        while (running) {
            try {
                AudioRingBuffer.ReadChunkResult result =
                        AudioRingBuffer.get().readChunk(AUDIO_READ_TIMEOUT_MS);
                if (result.status() == AudioRingBuffer.ReadStatus.DATA && result.chunk() != null) {
                    AudioChunk chunk = result.chunk();
                    if (server.connectionCount() > 0) {
                        server.broadcastBinary(AudioChunkCodec.encode(chunk));
                        audioChunks.incrementAndGet();
                    }
                }
                if (server.connectionCount() > 0) {
                    JpegFrame frame = VideoFrameBus.get().latest();
                    if (frame != null && frame.timestampNanos() > lastVideoTimestamp) {
                        lastVideoTimestamp = frame.timestampNanos();
                        server.broadcastBinary(VideoFrameCodec.encode(frame));
                        videoFrames.incrementAndGet();
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("{} [NET/PUSH] 推送异常（继续）", CaptureConstants.LOG_MARKER, t);
            }
        }
    }

    public long videoFrames() {
        return videoFrames.get();
    }

    public long audioChunks() {
        return audioChunks.get();
    }
}
