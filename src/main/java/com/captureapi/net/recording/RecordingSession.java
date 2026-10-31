package com.captureapi.net.recording;

import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.frame.VideoFrameBus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 录像会话（T4.10.1/I4.10.2）。
 *
 * <p>持续从 {@link VideoFrameBus} 取最新帧，写入 FFmpeg 子进程 stdin（image2pipe→libx264→mp4）。
 * 帧率/质量/目录复用配置；录制期间相机可正常 adjust（运镜）。FFmpeg 不存在/崩溃 → 停止并保留
 * 已编码部分，日志含 stderr 摘要。</p>
 *
 * <p><b>音频（I4.10.3）当前未实现</b>：记录为纯视频回退，`includeAudio=true` 时明确提示；音频
 * 二遍合流待 M3 多消费者/录音 tap 设计后补（不假称含音频轨）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RecordingSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final RecordingSession INSTANCE = new RecordingSession();
    private static final int STDERR_TAIL_LINES = 20;

    private final AtomicLong frames = new AtomicLong();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private volatile boolean running;
    private volatile String error;
    private volatile String outputPath;
    private volatile boolean audioAvailable;
    private Process process;
    private OutputStream processInput;
    private Thread captureThread;
    private Thread stderrThread;

    private RecordingSession() {
    }

    public static RecordingSession get() {
        return INSTANCE;
    }

    /** @return FFmpeg 是否可用（启动探测，不缓存） */
    public boolean isFfmpegAvailable() {
        try {
            Process probe = new ProcessBuilder(CaptureParams.get().ffmpegPath(), "-version")
                    .redirectErrorStream(true).start();
            boolean finished = probe.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                probe.destroyForcibly();
                return false;
            }
            return probe.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 开始录像（幂等约束：已有会话则拒绝）。
     *
     * @param params 录像参数
     * @return 启动后的状态
     */
    public synchronized RecordingStatus start(RecordingParams params) {
        if (running) {
            throw new IllegalStateException("录像已在进行中");
        }
        int active = CameraManager.get().activeCameraId();
        int cameraId = params.cameraId() == 0 ? active : params.cameraId();
        if (cameraId == 0 || CameraManager.get().get(cameraId) == null) {
            throw new IllegalStateException("录像相机不存在或未激活：cameraId=" + cameraId);
        }
        if (!isFfmpegAvailable()) {
            throw new IllegalStateException("FFmpeg 不可用（ffmpeg_path=" + CaptureParams.get().ffmpegPath() + "）");
        }
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve(CaptureParams.get().recordingOutputDir());
            Files.createDirectories(dir);
            String extension = "." + CaptureParams.get().recordingFormat();
            Path output = params.outputPath() != null ? Path.of(params.outputPath())
                    : dir.resolve("recording_" + com.captureapi.core.time.CaptureClock.get().nowNanos() + extension);
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            List<String> command = List.of(CaptureParams.get().ffmpegPath(), "-y",
                    "-f", "image2pipe", "-framerate", Integer.toString(params.fps()), "-i", "-",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                    output.toString());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            processInput = process.getOutputStream();
            outputPath = output.toString();
            audioAvailable = false;
            error = null;
            frames.set(0L);
            stderrTail.clear();
            running = true;
            startStderrConsumer(process.getErrorStream());
            captureThread = new Thread(this::captureLoop, "captureapi-recording");
            captureThread.setDaemon(true);
            captureThread.start();
            if (params.includeAudio()) {
                LOGGER.warn("{} [REC] 音频轨当前不可用（I4.10.3 待实现），回退纯视频：{}",
                        CaptureConstants.LOG_MARKER, output);
            }
            LOGGER.info("{} [REC/START] 录像开始：camera={}，fps={}，output={}", CaptureConstants.LOG_MARKER,
                    cameraId, params.fps(), output);
            return status();
        } catch (IOException e) {
            running = false;
            error = e.getMessage();
            closeProcess();
            throw new IllegalStateException("录像启动失败：" + e.getMessage(), e);
        }
    }

    /** 停止录像并回收 FFmpeg（≤5s）。 */
    public synchronized RecordingStatus stop() {
        if (!running && process == null) {
            return status();
        }
        running = false;
        try {
            if (captureThread != null) {
                captureThread.join(2000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeProcess();
        LOGGER.info("{} [REC/STOP] 录像停止：frames={}，output={}，error={}", CaptureConstants.LOG_MARKER,
                frames.get(), outputPath, error);
        return status();
    }

    /** @return 当前状态 */
    public RecordingStatus status() {
        return new RecordingStatus(running, frames.get(), outputPath, audioAvailable, error);
    }

    private void captureLoop() {
        long lastTimestamp = 0L;
        while (running) {
            try {
                JpegFrame frame = VideoFrameBus.get().latest();
                if (frame != null && frame.timestampNanos() > lastTimestamp) {
                    lastTimestamp = frame.timestampNanos();
                    processInput.write(frame.jpeg());
                    processInput.flush();
                    frames.incrementAndGet();
                } else {
                    Thread.sleep(5L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                error = t.getMessage();
                running = false;
                LOGGER.warn("{} [REC] 帧写入 FFmpeg 失败，停止录像", CaptureConstants.LOG_MARKER, t);
                break;
            }
        }
    }

    private void startStderrConsumer(InputStream errorStream) {
        stderrThread = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (stderrTail) {
                        stderrTail.addLast(line);
                        if (stderrTail.size() > STDERR_TAIL_LINES) {
                            stderrTail.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程结束
            }
        }, "captureapi-ffmpeg-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void closeProcess() {
        try {
            if (processInput != null) {
                processInput.close();
            }
        } catch (IOException ignored) {
            // 忽略
        }
        processInput = null;
        Process current = process;
        process = null;
        if (current != null) {
            try {
                if (!current.waitFor(5, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            }
            synchronized (stderrTail) {
                if (!stderrTail.isEmpty()) {
                    LOGGER.warn("{} [REC] FFmpeg stderr 末尾：{}", CaptureConstants.LOG_MARKER,
                            String.join(" | ", stderrTail));
                }
            }
        }
    }
}
