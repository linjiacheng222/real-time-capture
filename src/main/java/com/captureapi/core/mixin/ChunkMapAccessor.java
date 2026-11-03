package com.captureapi.core.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * ChunkMap 区块跟踪发送访问器（S11）。
 *
 * <p>{@code updateChunkTracking} 为 protected，且 AT 在对 dev 编译类路径不生效；用
 * {@link Invoker} 提升访问并随 refmap 自动重映射 SRG 名（m_183754_）。</p>
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {

    /**
     * 走原版完整区块发送路径（WithLight/BlockEntity/Forge hook）。
     *
     * @param player      目标玩家
     * @param pos         区块坐标
     * @param packetCache 原版可复用包缓存（单机每块 new）
     * @param wasAdded    true=发送/添加
     * @param wasRemoved  true=卸载
     */
    @Invoker("updateChunkTracking")
    void captureapi$updateChunkTracking(ServerPlayer player, ChunkPos pos,
                                        MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
                                        boolean wasAdded, boolean wasRemoved);
}
