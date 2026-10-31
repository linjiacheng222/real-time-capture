package com.captureapi.core.time;

import java.time.Instant;

/**
 * 墙上时钟白名单工具（I1.4.2）。
 *
 * <p>红线 4 的唯一例外：日志等人类可读时间戳可以使用墙上时钟；<b>禁止</b>用于帧/音频块时间戳
 * 或任何音画同步计算。源码检索规则（T1.4.2）只允许本类与 {@link CaptureClock} 出现时间 API。</p>
 */
public final class LogTimeConverter {

    private LogTimeConverter() {
        throw new AssertionError("no instances");
    }

    /**
     * 当前墙上时钟毫秒（仅日志展示用；白名单理由：人类可读时间，不参与音画时间戳）。
     *
     * @return epoch 毫秒
     */
    public static long currentTimeMillisForLog() {
        return System.currentTimeMillis();
    }

    /**
     * 墙上时钟毫秒→ISO-8601 字符串（仅日志展示）。
     *
     * @param epochMillis epoch 毫秒
     * @return ISO-8601 时间串
     */
    public static String formatForLog(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }
}
