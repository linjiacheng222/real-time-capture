package com.captureapi.video.render;

import com.captureapi.video.camera.CameraPose;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 相机帧内同步钩子（M2 冻结的 M2/M3 时序契约）。
 *
 * <p>时序契约：**修改 → 离屏渲染 → 恢复** 三步必须在同一离屏渲染帧内完成；两侧不共享状态，
 * 只共享时序。M2 在状态保存后、离屏渲染前调用 {@link #onBeforeOffscreenRender}，渲染返回后
 * 调用 {@link #onAfterOffscreenRender}；M3 注册实现（监听器空间同步）。契约变更须 M2/M3 同步评审。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class CameraSyncHook {

    /** 帧内同步实现（M3 提供）。 */
    public interface Listener {
        void beforeOffscreenRender(CameraPose pose);

        void afterOffscreenRender(CameraPose pose);
    }

    private static volatile Listener listener;

    private CameraSyncHook() {
        throw new AssertionError("no instances");
    }

    /** 注册实现（客户端 setup；幂等覆盖）。 */
    public static void install(Listener newListener) {
        listener = newListener;
    }

    /** @return 是否已安装实现 */
    public static boolean isInstalled() {
        return listener != null;
    }

    /**
     * 修改步（M2 调用；异常隔离，不阻断渲染）。
     *
     * @param pose 当前帧相机位姿
     */
    public static void onBeforeOffscreenRender(CameraPose pose) {
        Listener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.beforeOffscreenRender(pose);
        } catch (Throwable ignored) {
            // 该帧音频沿用上一帧监听器状态；渲染继续
        }
    }

    /**
     * 恢复步（M2 调用；异常隔离；恢复失败依赖游戏 tick 自愈）。
     *
     * @param pose 当前帧相机位姿
     */
    public static void onAfterOffscreenRender(CameraPose pose) {
        Listener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.afterOffscreenRender(pose);
        } catch (Throwable ignored) {
            // 恢复失败由游戏 listener tick 自愈
        }
    }
}
