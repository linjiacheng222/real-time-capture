package com.captureapi.core.mixin;

import com.captureapi.audio.AudioFilterRegistry;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 音频名单实际挂接（W-095 首版）：对 SoundEngine.play 入口按声音注册名过滤。
 *
 * <p>本版作用于整个客户端播放链（包括主画面与环回捕获）；名单为空时零行为。</p>
 */
@Mixin(SoundEngine.class)
public abstract class AudioFilterMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void captureapi$filterSound(SoundInstance instance, CallbackInfo ci) {
        if (instance == null || instance.getLocation() == null) {
            return;
        }
        if (!AudioFilterRegistry.get().allows(instance.getLocation().toString())) {
            ci.cancel();
        }
    }
}
