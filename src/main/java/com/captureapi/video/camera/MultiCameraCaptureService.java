package com.captureapi.video.camera;

import com.captureapi.core.constant.CaptureConstants;
import com.captureapi.core.resource.WorldResourceManager;
import com.captureapi.video.frame.JpegFrame;
import com.captureapi.video.stream.VideoStreamHub;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 多相机时间片渲染服务（P2.3 v1）。
 *
 * <p>当存在多个未暂停相机时，后台线程按固定间隔为每个相机提交一次实时调取（one-shot），
 * 结果写入 {@link CameraFrameStore}；主 active 流保持原有连续路径。这样可在不复制整条
 * FBO/PBO/编码管线的前提下提供按相机 id 的独立输出。</p>
 *
 * <p>限制：共享同一离屏 FBO/PBO，顺序渲染，不是每相机独立 FBO/PBO 的并行多路渲染；
 * 每次 one-shot 会短暂占用渲染帧，多相机时主 active 流会降帧。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class MultiCameraCaptureService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final long INTERVAL_MS = CaptureConstants.MULTI_CAMERA_INTERVAL_MS;
    private static final long ONESHOT_TIMEOUT_MS = CaptureConstants.MULTI_CAMERA_TIMEOUT_MS;

    private static volatile boolean installed;
    private static volatile Thread worker;

    private MultiCameraCaptureService() {
        throw new AssertionError("no instances");
    }

    /** 客户端 setup 安装（幂等）。 */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        worker = new Thread(MultiCameraCaptureService::loop, "captureapi-multicam");
        worker.setDaemon(true);
        worker.start();
        LOGGER.info("{} [MULTICAM] 多相机时间片服务已启动（interval={}ms，timeout={}ms）",
                CaptureConstants.LOG_MARKER, INTERVAL_MS, ONESHOT_TIMEOUT_MS);
    }

    private static void loop() {
        while (installed) {
            try {
                Thread.sleep(INTERVAL_MS);
                if (!WorldResourceManager.get().isWorldReady()) {
                    CameraFrameStore.clear();
                    continue;
                }
                List<CameraSpec> cameras = CameraManager.get().list();
                if (cameras.size() <= 1) {
                    CameraFrameStore.clear();
                    continue;
                }
                int active = CameraManager.get().activeCameraId();
                List<Integer> ids = new ArrayList<>();
                for (CameraSpec spec : cameras) {
                    // active 相机已有连续流（VideoFrameBus）；这里只补非 active 相机的按需帧
                    if (spec.id() <= 0 || spec.id() == active || spec.paused()) {
                        continue;
                    }
                    ids.add(spec.id());
                    renderCamera(spec);
                }
                CameraFrameStore.retainOnly(ids);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOGGER.warn("{} [MULTICAM] 时间片循环异常（继续）：{}",
                        CaptureConstants.LOG_MARKER, t.toString());
            }
        }
    }

    private static void renderCamera(CameraSpec spec) {
        try {
            CameraCreateParams params = CameraCreateParams.builder()
                    .position(spec.positionX(), spec.positionY(), spec.positionZ())
                    .rotation(spec.yawDegrees(), spec.pitchDegrees(), spec.rollDegrees())
                    .fov(spec.fovDegrees())
                    .resolution(spec.width(), spec.height())
                    .shape(spec.shape())
                    .build();
            JpegFrame frame = VideoStreamHub.get().requestOneShot(params, ONESHOT_TIMEOUT_MS)
                    .get(ONESHOT_TIMEOUT_MS + 1000L, TimeUnit.MILLISECONDS);
            CameraFrameStore.put(spec.id(), frame);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOGGER.debug("{} [MULTICAM] 相机 {} 时间片渲染失败：{}",
                    CaptureConstants.LOG_MARKER, spec.id(), t.toString());
        }
    }
}
