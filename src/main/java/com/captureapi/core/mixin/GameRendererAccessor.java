package com.captureapi.core.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * GameRenderer 状态访问器（T2.2.3）。
 *
 * <p>离屏渲染前关闭主手渲染，渲染后恢复原值；避免离屏画面出现玩家手部
 * 且不影响主画面配置。</p>
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

    @Accessor("renderHand")
    boolean captureapi$isRenderHand();

    @Accessor("renderHand")
    void captureapi$setRenderHand(boolean renderHand);
}
