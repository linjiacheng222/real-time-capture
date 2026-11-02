package com.captureapi.video.camera;

import com.captureapi.video.frame.JpegFrame;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 多相机最新帧缓存（P2.3 v1）。
 *
 * <p>单相机 active 流仍走 {@link VideoFrameBus}；非 active 相机由
 * {@link MultiCameraCaptureService} 以时间片方式独立渲染并放入本缓存，HTTP/Java 可按 id 读取。
 * 当前为独立输出 v1：共享 FBO/PBO 顺序渲染，尚未做到每相机独立 FBO/PBO 并行渲染。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CameraFrameStore {

    private static final Map<Integer, JpegFrame> FRAMES = new ConcurrentHashMap<>();

    private CameraFrameStore() {
        throw new AssertionError("no instances");
    }

    /** 写入指定相机最新帧。 */
    public static void put(int cameraId, JpegFrame frame) {
        if (cameraId > 0 && frame != null) {
            FRAMES.put(cameraId, frame);
        }
    }

    /** @return 指定相机最新帧；无则 null */
    public static JpegFrame get(int cameraId) {
        return FRAMES.get(cameraId);
    }

    /** @return 是否已有指定相机帧 */
    public static boolean has(int cameraId) {
        return FRAMES.containsKey(cameraId);
    }

    /** 删除指定相机帧（相机删除/暂停）。 */
    public static void remove(int cameraId) {
        FRAMES.remove(cameraId);
    }

    /** 仅保留给定相机 id 的帧（清理已删除相机）。 */
    public static void retainOnly(Collection<Integer> cameraIds) {
        FRAMES.keySet().retainAll(cameraIds);
    }

    /** 清空（世界退出/测试）。 */
    public static void clear() {
        FRAMES.clear();
    }

    /** @return 当前缓存相机数 */
    public static int size() {
        return FRAMES.size();
    }
}
