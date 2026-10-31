package com.captureapi.core.config;

import com.captureapi.CaptureInterface;
import com.captureapi.core.constant.CaptureConstants;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置加载/重载事件挂接（I1.5.3）。
 *
 * <p>订阅模组事件总线的 {@link ModConfigEvent.Loading} / {@link ModConfigEvent.Reloading}：
 * ① 过滤只属于本模组 spec 的回调（Forge 对该模组全部配置都会触发）；② 刷新
 * {@link CaptureParams} 快照；③ Reloading 时联动钩子（M4 预留）。</p>
 */
@Mod.EventBusSubscriber(modid = CaptureInterface.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModConfigEvents {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaptureConstants.LOG_MARKER);

    private ModConfigEvents() {
        throw new AssertionError("no instances");
    }

    @SubscribeEvent
    public static void onLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() != CaptureConfig.SPEC) {
            return;
        }
        LOGGER.info("{} 配置加载事件（Loading）：{}", CaptureConstants.LOG_MARKER, event.getConfig().getFileName());
        CaptureParams.get().refreshFromSpec(ReloadScope.INITIAL_LOAD);
    }

    @SubscribeEvent
    public static void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != CaptureConfig.SPEC) {
            return;
        }
        LOGGER.info("{} 配置重载事件（Reloading）：{}", CaptureConstants.LOG_MARKER, event.getConfig().getFileName());
        CaptureParams.get().refreshFromSpec(ReloadScope.RELOAD);
    }
}
