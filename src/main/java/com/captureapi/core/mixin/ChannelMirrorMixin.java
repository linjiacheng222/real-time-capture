package com.captureapi.core.mixin;

import com.captureapi.audio.multi.AudioMirrorProbe;
import com.captureapi.audio.multi.AudioSourceMirror;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
        AudioSourceMirror.onStaticBuffer((Channel) (Object) this, buffer);
    }

    @Inject(method = "attachBufferStream", at = @At("HEAD"))
    private void captureapi$probeStream(AudioStream stream, CallbackInfo ci) {
        AudioMirrorProbe.onStream();
    }

    @ModifyVariable(method = "attachBufferStream", at = @At("HEAD"), argsOnly = true, index = 1)
    private AudioStream captureapi$teeStream(AudioStream stream) {
        return AudioSourceMirror.wrapStream((Channel) (Object) this, stream);
    }

    @Inject(method = "play", at = @At("HEAD"))
    private void captureapi$probePlay(CallbackInfo ci) {
        AudioMirrorProbe.onPlay();
        AudioSourceMirror.onPlay((Channel) (Object) this);
    }

    @Inject(method = "pause", at = @At("HEAD"))
    private void captureapi$mirrorPause(CallbackInfo ci) {
        AudioSourceMirror.onPause((Channel) (Object) this);
    }

    @Inject(method = "unpause", at = @At("HEAD"))
    private void captureapi$mirrorUnpause(CallbackInfo ci) {
        AudioSourceMirror.onUnpause((Channel) (Object) this);
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void captureapi$mirrorStop(CallbackInfo ci) {
        AudioSourceMirror.onStop((Channel) (Object) this);
    }

    @Inject(method = "setSelfPosition", at = @At("HEAD"))
    private void captureapi$mirrorPosition(Vec3 position, CallbackInfo ci) {
        AudioSourceMirror.onPosition((Channel) (Object) this, position);
    }

    @Inject(method = "setPitch", at = @At("HEAD"))
    private void captureapi$mirrorPitch(float pitch, CallbackInfo ci) {
        AudioSourceMirror.onPitch((Channel) (Object) this, pitch);
    }

    @Inject(method = "setVolume", at = @At("HEAD"))
    private void captureapi$mirrorVolume(float volume, CallbackInfo ci) {
        AudioSourceMirror.onVolume((Channel) (Object) this, volume);
    }

    @Inject(method = "setLooping", at = @At("HEAD"))
    private void captureapi$mirrorLooping(boolean looping, CallbackInfo ci) {
        AudioSourceMirror.onLooping((Channel) (Object) this, looping);
    }

    @Inject(method = "disableAttenuation", at = @At("HEAD"))
    private void captureapi$mirrorDisableAttenuation(CallbackInfo ci) {
        AudioSourceMirror.onDisableAttenuation((Channel) (Object) this);
    }

    @Inject(method = "linearAttenuation", at = @At("HEAD"))
    private void captureapi$mirrorLinearAttenuation(float factor, CallbackInfo ci) {
        AudioSourceMirror.onLinearAttenuation((Channel) (Object) this, factor);
    }

    @Inject(method = "setRelative", at = @At("HEAD"))
    private void captureapi$mirrorRelative(boolean relative, CallbackInfo ci) {
        AudioSourceMirror.onRelative((Channel) (Object) this, relative);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void captureapi$probeDestroy(CallbackInfo ci) {
        AudioMirrorProbe.onDestroy();
        AudioSourceMirror.onDestroy((Channel) (Object) this);
    }
}
