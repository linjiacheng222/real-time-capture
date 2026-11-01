package com.captureapi.net.recording;

/**
 * 录像状态（T4.10.1）。
 *
 * @param running       是否录制中
 * @param frames        已写入帧数
 * @param outputPath    输出路径（未录制时 null）
 * @param audioAvailable 是否成功合入音频轨（首版：停录后 AAC 合流成功才为 true）
 * @param error         最近错误（无则 null）
 */
public record RecordingStatus(boolean running, long frames, String outputPath, boolean audioAvailable, String error) {
}
