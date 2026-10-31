package com.captureapi.video.stream;

import com.captureapi.video.camera.CameraCreateParams;
import com.captureapi.video.frame.JpegFrame;
import java.util.concurrent.CompletableFuture;

/**
 * 实时调取请求（I2.2.4）。
 *
 * <p>临时相机参数 + 结果 Future + 截止时刻；临时相机不进 CameraManager、不占 id，
 * 渲染一帧后丢弃。截止时刻用 CaptureClock（红线 4）。</p>
 */
public final class OneShotRequest {

    private final CameraCreateParams params;
    private final CompletableFuture<JpegFrame> future;
    private final long deadlineNanos;

    OneShotRequest(CameraCreateParams params, CompletableFuture<JpegFrame> future, long deadlineNanos) {
        this.params = params;
        this.future = future;
        this.deadlineNanos = deadlineNanos;
    }

    public CameraCreateParams params() {
        return params;
    }

    public CompletableFuture<JpegFrame> future() {
        return future;
    }

    /** @return 是否已过截止时刻（渲染线程下次轮询时判断） */
    public boolean isExpired(long nowNanos) {
        return nowNanos - deadlineNanos >= 0L;
    }

    /** @return 成功完成；已取消/已完成返回 false */
    public boolean complete(JpegFrame frame) {
        return future.complete(frame);
    }

    /** @return 异常完成；已取消/已完成返回 false */
    public boolean fail(Throwable error) {
        return future.completeExceptionally(error);
    }
}
