# 附录A 映射对照表（Yarn/intermediary → Mojmap）

> 状态：T1.7 v1（2026-10-31）。**状态机：只有「已确认」条目允许被业务代码引用**；
> 校验数据源：`docs/mapping-table-data.json`；自动化校验：`scripts/verify-mappings.py`（全链 Yarn→intermediary→official→Mojmap + Forge 类存在性）。
> 注意：Mojmap 名与 Yarn 包结构差异很大（如 `SoundEngine`→`com.mojang.blaze3d.audio.Library`），引用前必须查本表。

## 一、字段规范

| 字段 | 含义 |
|---|---|
| 序号 | A-xxx（class/方法行）、E-xxx（事件行）、P-xxx（参考模组自有类占位行） |
| 用途 | 哪个模块使用（M2/M3/M4/M5…） |
| 来源模组 | 首次出现该映射的参考对象（Mineshot/DebugBridge/Record-able/自研） |
| 原名 | Yarn（named）或 intermediary 名；参考模组自有类保留原 Yarn 名 |
| Mojmap 名 | 全限定名（`net.minecraft...` / `com.mojang...`） |
| 签名/差异说明 | 构造器/方法签名差异、事件对应 stage、包移动等 |
| 确认状态 | `已确认`（编译通过/运行冒烟覆盖）/`待验证 W-xxx` |
| 回填日期 | 最近一次验证日期 |

## 二、第一层：Minecraft 类映射（Yarn→Mojmap，全链已校验）

校验命令与证据：`python scripts/verify-mappings.py --forge-jar <forge recomp jar>` → **PASS=12 FAIL=0**（2026-10-31；输出存 `docs/evidence/T1.6-T1.7/verify-mappings.txt`）。

| 序号 | 用途 | 来源 | Yarn（named） | intermediary | Mojmap | 差异说明 | 状态 | 回填 |
|---|---|---|---|---|---|---|---|---|
| A-001 | M2 PoseStack/矩阵栈 | Mineshot/Pixelshot | `net.minecraft.client.util.math.MatrixStack` | `class_4587` | `com.mojang.blaze3d.vertex.PoseStack` | **包从 client.util.math 变为 com.mojang.blaze3d.vertex**；方法名 `push/pop/translate/multiply` 与 Mojmap 同名 | 已确认 | 2026-10-31 |
| A-002 | M2 相机位置向量 | Pixelshot/DebugBridge | `net.minecraft.util.math.Vec3d` | `class_243` | `net.minecraft.world.phys.Vec3` | 包改为 world.phys；字段 `x/y/z` 同名 | 已确认 | 2026-10-31 |
| A-003 | 资源标识符 | Pixelshot/DebugBridge | `net.minecraft.util.Identifier` | `class_2960` | `net.minecraft.resources.ResourceLocation` | 类名/包均不同；方法 `of/fromNamespaceAndPath` 需按 1.20.1 用法 | 已确认 | 2026-10-31 |
| A-004 | M2 相机投影/位置 | Mineshot/Pixelshot/Record-able | `net.minecraft.client.render.Camera` | `class_4184` | `net.minecraft.client.Camera` | 包不同、类名相同 | 已确认 | 2026-10-31 |
| A-005 | M3 音频设备初始化宿主 | Record-able | `net.minecraft.client.sound.SoundEngine` | `class_4225` | `com.mojang.blaze3d.audio.Library` | **类名与包均变**（Mojmap `Library`）；`init/close` 名字待实装核对 | 已确认（类） | 2026-10-31 |
| A-006 | M2 离屏渲染调用宿主 | Pixelshot/Mineshot/Record-able | `net.minecraft.client.render.GameRenderer` | `class_757` | `net.minecraft.client.renderer.GameRenderer` | 包 render→renderer | 已确认 | 2026-10-31 |
| A-007 | M2 世界渲染宿主 | Mineshot/Pixelshot | `net.minecraft.client.render.WorldRenderer` | `class_761` | `net.minecraft.client.renderer.LevelRenderer` | **类名 WorldRenderer→LevelRenderer** | 已确认 | 2026-10-31 |
| A-008 | M2 主循环/窗口入口 | Mineshot/Pixelshot/DebugBridge | `net.minecraft.client.MinecraftClient` | `class_310` | `net.minecraft.client.Minecraft` | 类名 MinecraftClient→Minecraft | 已确认 | 2026-10-31 |

## 三、第一层：事件/注入点对照

| 序号 | 用途 | Fabric 侧 | Forge 侧（Mojmap） | 差异说明 | 状态 |
|---|---|---|---|---|---|
| E-001 | M2 渲染阶段入口 | Pixelshot/Record-able 用 Mixin（无渲染事件 API 依赖） | `net.minecraftforge.client.event.RenderLevelStageEvent`（`Stage.AFTER_LEVEL`） | Forge 事件类已在 recomp jar 校验存在；AFTER_LEVEL 语义（光影/Embeddium 管线顺序）**待 M2 冒烟（W-023 残留）** | 类已确认；stage 待验证 |
| E-002 | M4/M5 登录/退出生命周期 | `ClientPlayConnectionEvents.JOIN/LEAVE` | `ClientPlayerNetworkEvent.LoggingIn/LoggingOut` | 本工程 `WorldLifecycleHandler` 已引用编译通过；真实进世界冒烟 MS1 | 已确认（编译） |
| E-003 | M4/M5 世界卸载兜底 | `ClientPlayConnectionEvents.DISCONNECT` | `net.minecraftforge.event.level.LevelEvent.Unload` | client `isClientSide()` 过滤；MS1 冒烟 | 已确认（编译） |
| E-004 | M2~M4 客户端 tick | `ClientTickEvents.END_CLIENT_TICK` | `net.minecraftforge.event.TickEvent.ClientTickEvent`（Phase.END） | 本工程 tick 兜底路径已在主菜单持续运行无异常 | 已确认 |
| E-005 | M3 SoundEngine 注入 | Record-able `SoundEngineMixin`（`@Mixin(class_4225)`，`init/close`） | 对应 `com.mojang.blaze3d.audio.Library` 的 `init/close`；M3 实装验证 | 注入点签名**待 M3 实装核对（W-024 残留）** | 待验证 |

## 四、第二层：参考模组自有类（原 Yarn 名；来源与许可已核）

| 序号 | 用途 | 来源（许可） | 原类名（Yarn） | 拟落包/处理 | 状态 |
|---|---|---|---|---|---|
| P-001 | M2 截图入口/状态恢复 | Pixelshot（MPL-2.0） | `fuzs.pixelshot.client.handler.ScreenshotHandler` | 参考；如复用文件保持 MPL | 待验证（W-025） |
| P-002 | M2 正交相机投影 | Pixelshot（MPL-2.0） | `fuzs.pixelshot.client.handler.OrthoViewHandler` | 参考/可复用（MPL 文件级） | 待验证（W-025） |
| P-003 | M2 注入点 | Pixelshot（MPL-2.0） | `fuzs.pixelshot.mixin.client.GameRendererMixin` / `LevelRendererMixin` | 参考注入策略（`renderLevel` ModifyVariable/WrapOperation、`renderClouds` cancel） | 待验证（W-025） |
| P-004 | M4 网络服务 | DebugBridge（MIT） | `com.debugbridge.core.server.BridgeServer` / `WebUiServer` | 可复用（保留版权） | 待验证（W-025） |
| P-005 | M4 JPEG/帧捕获 | DebugBridge（MIT） | `com.debugbridge.core.screenshot.JpegEncoder` / `recording.FrameCapturer` / `RecordingSession` | 可复用（保留版权） | 待验证（W-025） |
| P-006 | M3 环回采集 | Record-able（MIT） | `dev.recordable.OpenALLoopbackCapture` / `JavaAudioCapture` / `AudioCapture` | 可复用（保留版权）；M3 适配 1.20.1 | 待验证（W-025） |
| P-007 | M3 注入点 | Record-able（MIT） | `dev.recordable.mixin.SoundEngineMixin` / `GameRendererMixin` | 参考注入点；M3 实装 | 待验证（W-025） |
| P-008 | M2 设计前身 | Mineshot Revived（**GPL-3.0，禁复制**） | `FramebufferCapturer` / `FramebufferWriter` / `CaptureTask` / `RenderTickTask` / `CameraMixin` | **仅参考设计，禁止复制代码** | 仅参考 |
| P-009 | M2 正交相机 | Mineshot Revived（GPL） | `client.OrthoViewHandler` / `mixin.CameraInvoker` | 仅参考设计 | 仅参考 |

> 第二层条目由 `分析笔记/`（T1.6.2）产出；类名均来自实物源码/反编译，未臆造。

## 五、使用规则

1. 业务代码引用 Mojmap 名时，必须能在本表找到「已确认」条目；新条目先跑 `verify-mappings.py` 再入表；
2. 事件/注入点条目在 M2/M3 实装时用运行冒烟升级状态（I1.7.3）；
3. 本表每模块里程碑抽查一次（M2~M4 收口清单）。
