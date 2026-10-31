package com.captureapi.core.mixin;

import com.captureapi.core.CaptureMixinDiagnostics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin 基建自检（I1.1.1）。
 *
 * <p>唯一作用：让 Mixin 注解处理器在构建期产出 {@code captureapi.refmap.json}，
 * 并验证"mixin 配置 → refmap → 生产 jar → 运行期注入"整条骨架可用；
 * 运行期仅经 {@link CaptureMixinDiagnostics} 在 DEBUG 级别打印一行自检标记
 * （生产默认 INFO 级别不输出），不改变游戏行为。</p>
 *
 * <p>注意：本类不得声明静态字段（会被 Mixin 合并进目标类）；日志等辅助逻辑放
 * {@link CaptureMixinDiagnostics}。首个真实业务 Mixin 落地（M2/M3）后，本占位类可由
 * 对应任务移除或替换；当前保留以持续守护 MS1 退出条件中的"Mixin/AT/refmap 骨架可用"。</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "run", at = @At("HEAD"))
    private void captureapi$logMixinSmoke(CallbackInfo ci) {
        CaptureMixinDiagnostics.onMinecraftRunHead();
    }
}
