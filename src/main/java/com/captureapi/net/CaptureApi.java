package com.captureapi.net;

import com.captureapi.audio.AudioRingBuffer;
import com.captureapi.audio.timestamp.AudioChunk;
import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.video.camera.CameraCreateParams;
import com.captureapi.video.camera.CameraManager;
import com.captureapi.video.camera.CameraSpec;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.frame.VideoFrameBus;
import com.captureapi.video.stream.VideoStreamHub;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组内 Java API 门面（T4.7 / I4.7.1，二次开发唯一编程入口）。
 *
 * <p>同源约束：所有画面方法转发到与网络出口相同的 {@link VideoFrameBus} / {@link VideoStreamHub}，
 * 音频转发到 {@link AudioRingBuffer}，相机管理转发到 {@link CameraManager}；门面不缓存、不加工。
 * 线程语义：单帧/最新帧任意线程只读；调取返回 CompletableFuture（完成在渲染线程）；
 * 相机管理方法可任意线程（CameraManager 线程安全）。</p>
 *
 * <p>边界：音频截面（虚拟位）语义与实现待 W-071 冻结，当前以明确 UnsupportedOperationException
 * 标注（不为假实现）。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CaptureApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private CaptureApi() {
        throw new AssertionError("no instances");
    }

    // ===== 画面 =====

    /** @return 最新 JPEG 帧（任意线程；从未发布时 null；与网络出口同源） */
    public static JpegFrame latestFrame() {
        return VideoFrameBus.get().latest();
    }

    /**
     * 实时调取一帧（临时相机，异步；与流式同帧互斥）。
     *
     * @param params        临时相机参数
     * @param timeoutMillis 超时毫秒
     * @return JPEG 帧 Future（世界未就绪/超时/渲染冲突时异常完成）
     */
    public static CompletableFuture<JpegFrame> grabFrame(CameraCreateParams params, long timeoutMillis) {
        return VideoStreamHub.get().requestOneShot(params, timeoutMillis);
    }

    // ===== 音频 =====

    /** @return 最近写入的音频块（非阻塞、非消费；无数据时 null） */
    public static AudioChunk latestAudioChunk() {
        return AudioRingBuffer.get().peekLatest();
    }

    /**
     * 音频截面（虚拟位置）——**待 W-071 语义冻结/实现**。
     *
     * @return 明确失败 Future（不为假实现）
     */
    public static CompletableFuture<AudioChunk> grabAudioSection(double x, double y, double z, int chunkCount) {
        if (chunkCount < 1 || chunkCount > 100) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("chunkCount 必须在 [1,100]"));
        }
        LOGGER.warn("{} [API] grabAudioSection 尚未实现（W-071），明确失败而非假返回", CaptureConstants.LOG_MARKER);
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("音频截面待 W-071 语义冻结后实现"));
    }

    // ===== 相机管理（代理 CameraManager） =====

    /** 创建相机（任意线程；首个自动 active）。 */
    public static CameraSpec createCamera(CameraCreateParams params) {
        return CameraManager.get().create(params);
    }

    /** 调整相机参数。 */
    public static CameraSpec adjustCamera(int id, CameraCreateParams params) {
        return CameraManager.get().adjust(id, params);
    }

    /** 激活相机。 */
    public static CameraSpec activateCamera(int id) {
        return CameraManager.get().activate(id);
    }

    /** 暂停相机。 */
    public static CameraSpec pauseCamera(int id) {
        return CameraManager.get().pause(id);
    }

    /** 恢复相机。 */
    public static CameraSpec resumeCamera(int id) {
        return CameraManager.get().resume(id);
    }

    /** 删除相机。 */
    public static void deleteCamera(int id) {
        CameraManager.get().delete(id);
    }

    /** @return 相机列表（id 升序副本） */
    public static List<CameraSpec> listCameras() {
        return CameraManager.get().list();
    }

    /** @return 当前 active 相机 id；0=无 */
    public static int activeCameraId() {
        return CameraManager.get().activeCameraId();
    }
}
