package com.captureapi.core.mixin;

import com.mojang.blaze3d.audio.SoundBuffer;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** SoundBuffer PCM/format 访问器（Phase 2 声源镜像数据源）。 */
@Mixin(SoundBuffer.class)
public interface SoundBufferAccessor {

    @Accessor("data")
    ByteBuffer captureapi$getData();

    @Accessor("format")
    AudioFormat captureapi$getFormat();
}
