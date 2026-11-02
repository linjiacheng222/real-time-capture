package com.captureapi.core.mixin;

import com.captureapi.video.filter.RenderFilterRegistry;
import com.captureapi.video.render.OffscreenPoseContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 渲染名单实际挂接（W-095 首版）：仅在离屏渲染窗口内按实体注册名过滤实体渲染。
 *
 * <p>主画面不受影响；方块/方块实体/粒子不在本版范围。名单为空时零行为。</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class RenderFilterMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void captureapi$filterOffscreenEntity(Entity entity, double x, double y, double z,
                                                  float yaw, float tickDelta, PoseStack poseStack,
                                                  MultiBufferSource buffers, int packedLight,
                                                  CallbackInfo ci) {
        if (OffscreenPoseContext.current() == null || entity == null) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id != null && !RenderFilterRegistry.get().allows(id.toString())) {
            ci.cancel();
        }
    }
}
