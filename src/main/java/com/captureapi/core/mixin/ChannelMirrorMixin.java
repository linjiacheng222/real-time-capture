package com.captureapi.core.mixin;

import com.captureapi.audio.multi.AudioMirrorProbe;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Channel 镜像钩子骨架（Phase 0 只做探针；Phase 2 接入真实镜像）。
 *
 * <p>注入点均为 HEAD/TAIL 观测，不改变原 Channel 行为。</p>
 */
@Mixin(Channel.class)
public abstract class ChannelMirrorMixin {

    @Inject(method = "attachStaticBuffer", at = @At("HEAD"))
    private void captureapi$probeStatic(SoundBuffer buffer, CallbackInfo ci) {
        AudioMirrorProbe.onStaticBuffer(buffer);
    }

    @Inject(method = "attachBufferStream", at = @At("HEAD"))
    private void captureapi$probeStream(AudioStream stream, CallbackInfo ci) {
        AudioMirrorProbe.onStream();
    }

    @Inject(method = "play", at = @At("HEAD"))
    private void captureapi$probePlay(CallbackInfo ci) {
        AudioMirrorProbe.onPlay();
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void captureapi$probeDestroy(CallbackInfo ci) {
        AudioMirrorProbe.onDestroy();
    }
}
