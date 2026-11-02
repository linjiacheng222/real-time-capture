package com.captureapi.audio.multi;

/**
 * 音频通道公开信息快照。
 *
 * @param id        通道 id（0=player；camera 使用 cameraId；virtual=-1 内部）
 * @param kind      类型
 * @param cameraId  相机 id（非 camera 为 -1）
 * @param active    是否运行中
 * @param written   已写 PCM 拍数
 * @param dropped   丢弃拍数
 * @param errors    worker 错误计数
 */
public record AudioChannelInfo(int id, AudioChannelKind kind, int cameraId, boolean active,
                               long written, long dropped, long errors) {
}
