package com.captureapi.core.mixin;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Channel 私有状态访问器（Phase 2 流 tee/source 映射）。 */
@Mixin(Channel.class)
public interface ChannelAccessor {

    @Accessor("stream")
    AudioStream captureapi$getStream();

    @Accessor("stream")
    void captureapi$setStream(AudioStream stream);

    @Accessor("source")
    int captureapi$getSource();
}
