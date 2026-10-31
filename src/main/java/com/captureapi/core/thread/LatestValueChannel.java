package com.captureapi.core.thread;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 跨线程"最新值"通道（I1.3.3）。
 *
 * <p>语义：仅保留最新一次发布的对象；生产线程 {@link #publish(Object)}，任意线程
 * {@link #latest()} 读取。渲染线程 → 网络线程的帧数据等传递只允许经本通道（或不可变对象 +
 * volatile/AtomicReference），禁止直接共享可变缓冲。</p>
 *
 * <p>契约：<b>发布即不可变</b> —— 发布对象必须在下游线程中只读；M2 编码帧对象须为防御性副本。
 * 本类不负责对象不可变性检查，由 M2/M4 的评审与测试背书。</p>
 *
 * @param <T> 载荷类型（通常为不可变帧对象）
 */
public final class LatestValueChannel<T> {

    private final AtomicReference<T> latest = new AtomicReference<>();
    private final AtomicLong publishedCount = new AtomicLong();

    /**
     * 发布一个新值（仅数据生产线程调用）。
     *
     * @param value 最新对象；不可为 null
     */
    public void publish(T value) {
        Objects.requireNonNull(value, "LatestValueChannel 载荷不可为 null");
        latest.set(value);
        publishedCount.incrementAndGet();
    }

    /** @return 当前最新对象；从未发布/已清空时为 null（任意线程安全读取） */
    public T latest() {
        return latest.get();
    }

    /**
     * @param fallback 无最新值时的回退值
     * @return 当前最新对象或回退值
     */
    public T latestOr(T fallback) {
        T value = latest.get();
        return value != null ? value : fallback;
    }

    /** 清空最新值（不重置发布计数，便于诊断丢帧/复位场景）。 */
    public void clear() {
        latest.set(null);
    }

    /** @return 累计发布次数（诊断用；跨线程安全） */
    public long publishedCount() {
        return publishedCount.get();
    }
}
