package com.captureapi.core.mixin;

import com.captureapi.audio.AudioCaptureManager;
import com.mojang.blaze3d.audio.Library;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.lwjgl.openal.ALC10;

/**
 * 音频环回注入（T3.1，1.20.1 Library 注入点）。
 *
 * <p>四个注入：openDeviceOrFallback 重定向（环回设备）、alcCreateContext 重定向（注入格式属性）、
 * init TAIL（启动采集线程）、cleanup HEAD（停止采集）。1.20.1 不存在 Fabric 版的
 * SoundEngine#openDeviceOrFallback，故按 Library 重写。</p>
 */
@Mixin(Library.class)
public abstract class AudioLibraryMixin {

    @Redirect(method = "init", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/audio/Library;openDeviceOrFallback(Ljava/lang/String;)J"))
    private long captureapi$redirectOpenDevice(String requested) {
        return AudioCaptureManager.get().openDeviceOrFallback(requested);
    }

    @Redirect(method = "init", remap = false, at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"))
    private long captureapi$redirectCreateContext(long device, IntBuffer attrs) {
        return AudioCaptureManager.get().createContext(device, attrs);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void captureapi$afterInit(CallbackInfo ci) {
        AudioCaptureManager.get().onLibraryInitialized();
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void captureapi$beforeCleanup(CallbackInfo ci) {
        AudioCaptureManager.get().onLibraryCleanup();
    }
}
