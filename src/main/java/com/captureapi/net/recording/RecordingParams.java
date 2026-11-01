package com.captureapi.net.recording;

/**
 * 录像参数（T4.10.1，不可变）。
 *
 * @param cameraId     数据源相机 id（0=active）
 * @param fps          帧率（默认 fps_max 由调用方填）
 * @param jpegQuality  JPEG 质量（默认 jpeg_quality）
 * @param includeAudio 是否请求音频轨（当前实现回退纯视频并明确提示）
 * @param outputPath   输出文件路径（null=recording_output_dir 自动命名）
 */
final class RecordingLimits {
    /** 录制帧率上界（附录 B 同值）。 */
    static final int MAX_FPS = 240;

    private RecordingLimits() {
    }
}

public record RecordingParams(int cameraId, int fps, int jpegQuality, boolean includeAudio, String outputPath) {

    public RecordingParams {
        if (fps <= 0 || fps > RecordingLimits.MAX_FPS) {
            throw new IllegalArgumentException("fps 必须在 [1,240]");
        }
        if (jpegQuality < 0 || jpegQuality > 100) {
            throw new IllegalArgumentException("jpegQuality 必须在 [0,100]");
        }
    }
}
