package com.captureapi.core.time;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 时钟诊断自测（I1.4.1 ④，等价 SelfTestTickSource）。
 *
 * <p>仅当 {@code clock_logging=true}（T1.5 配置）时，每秒一次在客户端 tick 中打印
 * {@link CaptureClock#nowNanos()} 与帧间隔，验证单调性与时间基座；默认关闭（红线 4 默认参数纪律）。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClockTickLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);
    private static final long LOG_INTERVAL_NANOS = CaptureConstants.NANOS_PER_SECOND;

    private static long lastLogNanos;
    private static int ticksSinceLog;

    private ClockTickLogger() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        CaptureClock clock = CaptureClock.get();
        if (!clock.isLogging()) {
            lastLogNanos = 0L;
            ticksSinceLog = 0;
            return;
        }
        long now = clock.nowNanos();
        ticksSinceLog++;
        if (lastLogNanos == 0L) {
            lastLogNanos = now;
            ticksSinceLog = 0;
            return;
        }
        long delta = now - lastLogNanos;
        if (delta >= LOG_INTERVAL_NANOS) {
            LOGGER.info("{} 时钟自测：nowNanos={}，近 {} tick 间隔={}ms（单调性观察）",
                    CaptureConstants.LOG_MARKER, now, ticksSinceLog, clock.toMillisForLog(delta));
            lastLogNanos = now;
            ticksSinceLog = 0;
        }
    }
}
