package com.captureapi.core.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelEntityGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * ClientLevel 实体表访问器（T2.1.2）。
 *
 * <p>绑定实体相机需要在渲染线程按 UUID 解析实体；{@code ClientLevel#getEntities()} 为 protected，
 * 用 Mixin {@link Invoker} 访问（比 Access Transformer 更简单、且随 refmap 自动重映射 SRG 名）。</p>
 */
@Mixin(ClientLevel.class)
public interface ClientLevelAccessor {

    /**
     * @return 客户端实体表（可按 UUID 查询）
     */
    @Invoker("getEntities")
    LevelEntityGetter<Entity> captureapi$getEntities();
}
