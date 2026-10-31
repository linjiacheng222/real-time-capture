package com.captureapi.net;

import java.nio.file.Path;

/**
 * 统一帧获取结果（T4.9 / I4.9.1）。
 *
 * @param outputMethod 输出方式（FILE/HTTP）
 * @param location     文件路径或 URL
 * @param timestampNanos 帧时间戳
 * @param width        帧宽
 * @param height       帧高
 * @param sizeBytes    输出字节数
 */
public record FrameResult(
        FrameRequest.OutputMethod outputMethod,
        String location,
        long timestampNanos,
        int width,
        int height,
        int sizeBytes) {

    public static FrameResult file(Path path, long timestampNanos, int width, int height, int sizeBytes) {
        return new FrameResult(FrameRequest.OutputMethod.FILE, path.toString(), timestampNanos, width, height,
                sizeBytes);
    }

    public static FrameResult http(String url, long timestampNanos, int width, int height, int sizeBytes) {
        return new FrameResult(FrameRequest.OutputMethod.HTTP, url, timestampNanos, width, height, sizeBytes);
    }

    public boolean isFile() {
        return outputMethod == FrameRequest.OutputMethod.FILE;
    }
}
