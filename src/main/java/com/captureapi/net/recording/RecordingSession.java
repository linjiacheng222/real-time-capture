package com.captureapi.net.recording;

import com.captureapi.audio.AudioRingBuffer;
import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.core.config.CaptureParams;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.encode.JpegEncoder;
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
 * <p>音频（I4.10.3 首版）：录制期间从音频环形缓冲快照最近拍写入临时 PCM，停止时用 FFmpeg
 * 第二输入流合流为 AAC 音轨；PCM 缺失时回退纯视频并在状态中如实标记 audioAvailable=false。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class RecordingSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final RecordingSession INSTANCE = new RecordingSession();
    private static final int STDERR_TAIL_LINES = 20;
    /** 音频采集轮询间隔（毫秒）。 */
    private static final long AUDIO_POLL_MS = 3L;
    /** 音频时间戳断档告警阈值（毫秒）。 */
    private static final long AUDIO_GAP_WARN_MS = 50L;
    /** 毫秒→纳秒换算。 */
    private static final long NANOS_PER_MS = 1_000_000L;
    /** 录像停止时采集/音频线程等待上限（毫秒）。 */
    private static final long THREAD_JOIN_MS = 1000L;
    /** 合流等待上限（秒）。 */
    private static final long MUX_TIMEOUT_SECONDS = 60L;
    /** AAC 音频码率。 */
    private static final String AUDIO_BITRATE = "128k";

    private final AtomicLong frames = new AtomicLong();
    private final JpegEncoder jpegEncoder = new JpegEncoder();
    private final Deque<String> stderrTail = new ArrayDeque<>();

    private volatile boolean running;
    private volatile String error;
    private volatile String outputPath;
    private volatile boolean audioAvailable;
    private volatile boolean includeAudio;
    private volatile int requestedJpegQuality;
    private Path finalOutputPath;
    private Path videoTempPath;
    private Path audioTempPath;
    private OutputStream audioOutput;
    private Thread audioThread;
    private final AtomicLong audioChunks = new AtomicLong();
    private long lastAudioEndNanos;
    private long audioSampleRate;
    private int audioChannels;
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
            finalOutputPath = output;
            includeAudio = params.includeAudio();
            requestedJpegQuality = params.jpegQuality();
            Path videoOutput = output;
            if (includeAudio) {
                // 先录纯视频临时文件，停止时再用第二输入流把 PCM 合流为最终 mp4
                videoOutput = output.resolveSibling(output.getFileName() + ".video.mp4");
                audioTempPath = Files.createTempFile(dir, "captureapi-audio-", ".pcm");
                videoTempPath = videoOutput;
            }
            List<String> command = List.of(CaptureParams.get().ffmpegPath(), "-y",
                    "-f", "image2pipe", "-framerate", Integer.toString(params.fps()), "-i", "-",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                    videoOutput.toString());
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
            if (includeAudio) {
                audioSampleRate = CaptureParams.get().sampleRate();
                audioChannels = CaptureParams.get().channels();
                audioOutput = Files.newOutputStream(audioTempPath);
                audioChunks.set(0L);
                lastAudioEndNanos = 0L;
                audioThread = new Thread(this::audioLoop, "captureapi-recording-audio");
                audioThread.setDaemon(true);
                audioThread.start();
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
                captureThread.join(THREAD_JOIN_MS);
            }
            if (audioThread != null) {
                audioThread.join(THREAD_JOIN_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeAudioOutput();
        closeProcess();
        finalizeAudio();
        LOGGER.info("{} [REC/STOP] 录像停止：frames={}，audioChunks={}，audioAvailable={}，output={}，error={}",
                CaptureConstants.LOG_MARKER, frames.get(), audioChunks.get(), audioAvailable, outputPath, error);
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
                    byte[] jpeg = frame.jpeg();
                    if (requestedJpegQuality != CaptureParams.get().jpegQuality()) {
                        jpeg = jpegEncoder.transcode(jpeg, requestedJpegQuality);
                    }
                    processInput.write(jpeg);
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

    /** 音频采样线程：快照环形缓冲最近拍并写临时 PCM（不消费 WS/录像游标）。 */
    private void audioLoop() {
        while (running) {
            try {
                AudioChunk chunk = AudioRingBuffer.get().snapshotRecent(1);
                if (chunk != null && chunk.endNanos() > lastAudioEndNanos) {
                    if (lastAudioEndNanos > 0L
                            && chunk.startNanos() - lastAudioEndNanos > AUDIO_GAP_WARN_MS * NANOS_PER_MS) {
                        LOGGER.warn("{} [REC/AUDIO] 音频断档约 {}ms", CaptureConstants.LOG_MARKER,
                                (chunk.startNanos() - lastAudioEndNanos) / 1_000_000L);
                    }
                    audioOutput.write(chunk.pcm());
                    lastAudioEndNanos = chunk.endNanos();
                    audioChunks.incrementAndGet();
                } else {
                    Thread.sleep(AUDIO_POLL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                error = "音频写入失败：" + t.getMessage();
                LOGGER.warn("{} [REC/AUDIO] 写入临时 PCM 失败", CaptureConstants.LOG_MARKER, t);
                running = false;
                break;
            }
        }
    }

    private void closeAudioOutput() {
        try {
            if (audioOutput != null) {
                audioOutput.close();
            }
        } catch (IOException ignored) {
            // 忽略
        }
        audioOutput = null;
    }

    /** 停止后把纯视频临时文件与 PCM 合流为最终文件；失败回退纯视频并标记 audioAvailable=false。 */
    private void finalizeAudio() {
        if (!includeAudio || finalOutputPath == null) {
            return;
        }
        Path output = finalOutputPath;
        Path videoTemp = videoTempPath;
        Path audioTemp = audioTempPath;
        boolean hasAudio = audioChunks.get() > 0L && audioTemp != null && Files.exists(audioTemp);
        if (videoTemp != null && Files.exists(videoTemp)) {
            if (hasAudio) {
                try {
                    List<String> mux = List.of(CaptureParams.get().ffmpegPath(), "-y",
                            "-i", videoTemp.toString(),
                            "-f", "s16le", "-ar", Long.toString(audioSampleRate),
                            "-ac", Integer.toString(audioChannels), "-i", audioTemp.toString(),
                            "-map", "0:v:0", "-map", "1:a:0",
                            "-c:v", "copy", "-c:a", "aac", "-b:a", AUDIO_BITRATE,
                            "-shortest", "-movflags", "+faststart", output.toString());
                    ProcessBuilder builder = new ProcessBuilder(mux);
                    builder.redirectErrorStream(true);
                    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    Process muxProcess = builder.start();
                    boolean finished = muxProcess.waitFor(MUX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (!finished) {
                        muxProcess.destroyForcibly();
                    }
                    if (finished && muxProcess.exitValue() == 0 && Files.exists(output)
                            && Files.size(output) > 0L) {
                        audioAvailable = true;
                        LOGGER.info("{} [REC/AUDIO] 合流完成：{}（{} 拍）", CaptureConstants.LOG_MARKER,
                                output, audioChunks.get());
                    } else {
                        LOGGER.warn("{} [REC/AUDIO] FFmpeg 合流失败，回退纯视频", CaptureConstants.LOG_MARKER);
                        moveVideoFallback(videoTemp, output);
                    }
                } catch (Exception e) {
                    LOGGER.warn("{} [REC/AUDIO] 合流异常，回退纯视频：{}", CaptureConstants.LOG_MARKER, e.getMessage());
                    moveVideoFallback(videoTemp, output);
                } finally {
                    deleteQuietly(audioTemp);
                }
            } else {
                moveVideoFallback(videoTemp, output);
            }
        }
        if (audioTemp != null) {
            deleteQuietly(audioTemp);
        }
        if (videoTemp != null) {
            deleteQuietly(videoTemp);
        }
        outputPath = output.toString();
        includeAudio = false;
    }

    private void moveVideoFallback(Path videoTemp, Path output) {
        try {
            Files.move(videoTemp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            audioAvailable = false;
        } catch (IOException e) {
            error = "录像回退移动失败：" + e.getMessage();
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            if (path != null) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 忽略
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
