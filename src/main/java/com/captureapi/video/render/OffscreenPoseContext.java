package com.captureapi.video.render;

import com.captureapi.video.camera.CameraPose;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 离屏渲染相机位姿上下文（S1 修复）。
 *
 * <p>{@code GameRenderer#renderLevel} 内部会调用 {@code Camera#setup(...)}，用玩家实体位置/角度
 * 覆盖我们在渲染前写入的自定义机位。这里用线程局部上下文让 {@code CameraPoseMixin} 在
 * {@code Camera#setup} 的 TAIL 重新应用离屏机位；上下文仅在离屏 {@code renderLevel} 调用窗口
 * 内生效，主画面渲染不受影响。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class OffscreenPoseContext {

    private static final ThreadLocal<Deque<CameraPose>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private OffscreenPoseContext() {
        throw new AssertionError("no instances");
    }

    /** 进入离屏渲染窗口。 */
    public static void push(CameraPose pose) {
        STACK.get().push(pose);
    }

    /** 退出离屏渲染窗口。 */
    public static void pop() {
        Deque<CameraPose> stack = STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            STACK.remove();
        }
    }

    /** @return 当前离屏机位；主画面渲染时为 null */
    public static CameraPose current() {
        Deque<CameraPose> stack = STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }
}
