package com.captureapi.core.thread;

/**
 * 单写多读（MPSC 的对偶：单生产者）有界环形缓冲接口骨架（I1.3.3）。
 *
 * <p>M1 只定义线程语义与接口，不实现音频逻辑；容量、丢帧策略等参数由 M3（T3.2）细化。
 * 典型使用：M3 音频采集线程为唯一写端，网络/消费线程为读端；有界容量下写端在满时按 M3
 * 策略处理（丢最旧或计数），读端在空时按 M3 策略处理（等待超时/静音填充）。</p>
 *
 * <p><b>线程语义（实现方必须满足）</b>：</p>
 * <ol>
 *   <li>{@link #offer(Object)} 仅允许单一写线程调用；</li>
 *   <li>{@link #poll()} / {@link #poll(long)} 允许多个读线程并发调用（交给 M3 的具体实现决定读端模型）；</li>
 *   <li>容量在构造期固定，运行期不扩容；</li>
 *   <li>实现必须保证无锁或有序加锁下的可见性（不得返回部分写入的数据）。</li>
 * </ol>
 *
 * @param <T> 载荷类型（通常为不可变音频块/字节块）
 */
public interface MpscRingBuffer<T> {

    /**
     * 尝试写入一个元素（仅单一写线程调用）。
     *
     * @param value 元素；不可为 null
     * @return true 写入成功；false 表示本次写被拒绝（容量/策略由实现与 M3 决定）
     */
    boolean offer(T value);

    /**
     * 取出一个元素（读端调用；非阻塞）。
     *
     * @return 取出的元素；缓冲为空时返回 null
     */
    T poll();

    /**
     * 带超时取出一个元素（读端调用）。
     *
     * @param timeoutMillis 最长等待毫秒数（≥0）
     * @return 取出的元素；超时/为空时返回 null
     * @throws InterruptedException 等待被中断
     */
    T poll(long timeoutMillis) throws InterruptedException;

    /** @return 当前可读元素数（近似值，供水位诊断） */
    int size();

    /** @return 固定容量 */
    int capacity();

    /** 清空缓冲（生命周期结束时调用；由实现定义线程安全语义）。 */
    void clear();
}
