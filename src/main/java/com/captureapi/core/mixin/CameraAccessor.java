package com.captureapi.core.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Camera 状态访问器（T2.3.2）。
 *
 * <p>离屏渲染需要临时把原版主相机改成自定义机位，渲染后恢复。Camera 的位姿字段/保护方法
 * 不可直接访问，使用 Mixin accessor/invoker（避免 Access Transformer 重映射产物问题）。
 * 最终向量 forward/up/left 为 final 字段，通过 getter 取得可变引用后原地写回。</p>
 *
 * <p>仅由渲染线程在离屏流程窗口内调用。</p>
 */
@Mixin(Camera.class)
public interface CameraAccessor {

    @Accessor("position")
    Vec3 captureapi$getPosition();

    @Accessor("xRot")
    float captureapi$getXRot();

    @Accessor("xRot")
    void captureapi$setXRot(float xRot);

    @Accessor("yRot")
    float captureapi$getYRot();

    @Accessor("yRot")
    void captureapi$setYRot(float yRot);

    @Accessor("rotation")
    Quaternionf captureapi$getRotation();

    @Accessor("forwards")
    Vector3f captureapi$getForwards();

    @Accessor("up")
    Vector3f captureapi$getUp();

    @Accessor("left")
    Vector3f captureapi$getLeft();

    @Accessor("initialized")
    boolean captureapi$isInitialized();

    @Accessor("initialized")
    void captureapi$setInitialized(boolean initialized);

    @Accessor("detached")
    boolean captureapi$isDetached();

    @Accessor("detached")
    void captureapi$setDetached(boolean detached);

    @Accessor("level")
    BlockGetter captureapi$getLevel();

    @Accessor("level")
    void captureapi$setLevel(BlockGetter level);

    @Accessor("entity")
    Entity captureapi$getEntity();

    @Accessor("entity")
    void captureapi$setEntity(Entity entity);

    @Invoker("setPosition")
    void captureapi$setPosition(Vec3 position);

    @Invoker("setRotation")
    void captureapi$setRotation(float yaw, float pitch);
}
