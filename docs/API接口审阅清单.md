# CaptureAPI 接口审阅清单（给计划端）

> 2026-11-02 更新：计划端已完成统一 capture/v2 指令整肃；当前实际用法以 `docs/API使用说明.md` 为准，本文保留为审阅历史。

> 目的：在正式接口冻结前，把**现有 Java API / WS 控制 / 临时聊天命令**三层入口盘点清楚，标出实现状态、语义缺口与需要裁决的设计问题。
> 本清单只做现状记录与建议，不改变实现；聊天命令集**未做游戏内运行测试**（用户要求先审阅）。
> 生成日期：2026-11-02；对应本地 `main`（未推送）。

---

## 一、入口分层（当前实际存在三套）

| 层 | 位置 | 定位 | 现状 |
|---|---|---|---|
| Java 门面 | `com.captureapi.net.CaptureApi` | 二次开发唯一编程入口（文档口径） | 画面/音频/相机已实现；录像走 `RecordingSession`，不在门面 |
| WS 控制 | `CaptureCommandDispatcher` + `CaptureWebSocketServer` | 网络控制/自动化 | 33 个 action；部分 test-only 已门控 |
| 聊天命令 | ~~`com.captureapi.client.command.CaptureCommands`~~ | **已按用户要求移除（2026-11-02）**，仅保留历史审阅记录 | 不再存在于当前源码/jar |

**核心问题**：三层入口没有统一的契约来源；WS schema 文档只覆盖相机域，聊天命令又直接调用混合的 WS/Java/内部类，正式接口前需确定“哪一层是对外产品接口”。

---

## 二、Java API 清单（`CaptureApi`）

| 方法 | 线程约束 | 实现状态 | 备注 |
|---|---|---|---|
| `latestFrame()` | 任意线程只读 | 已实现 | 返回最新 `JpegFrame`；无帧 null |
| `grabFrame(CameraCreateParams, long)` | 异步，Future 完成在渲染线程 | 已实现 | 临时相机；与流式同帧互斥 |
| `captureFrame(FrameRequest)` | **不可渲染线程**（会阻塞等待） | 已实现（有约束） | 文件/HTTP 输出；`continuous` 与 `includeAudio` 直接抛未实现 |
| `latestAudioChunk()` | 任意线程 | 已实现 | 非消费快照 |
| `grabAudioSection(x,y,z,chunks)` | 异步 CompletableFuture | **首版已实现** | 虚拟 listener 窗口 + 环形缓冲最近 N 拍；W-071 语义未最终冻结 |
| `createCamera/adjustCamera/activate/pause/resume/delete/list/activeId` | 任意线程（CameraManager 线程安全） | 已实现 | 直接代理 CameraManager |
| **录像** | `RecordingSession.start/stop/status` 不在 CaptureApi | 已实现 | 需 FFmpeg；`stop()` 包含合流/等待，可能阻塞，调用方需避免渲染线程 |

### 数据对象

| 对象 | 字段/职责 | 问题 |
|---|---|---|
| `CameraCreateParams` | 绝对位置/旋转/FOV/分辨率/绑定实体/形状 builder | 与 `CameraSpec`、WS JSON 三套表示；绑定实体与位置互斥规则分散 |
| `FrameRequest` | 输出方式 FILE/HTTP、格式 JPEG/PNG、临时/连续、位置或绑定实体、质量、音频标志 | `includeAudio` 未实现但字段保留；`continuous` 未实现；PNG 实际先取 JPEG 再解码 |
| `FrameResult` | 输出方式、文件/URL、时间戳、宽高、字节数 | 仅 FILE/HTTP 两态 |
| `AudioChunk` | start/end/sampleCount/PCM | 首版虚拟位置快照存在并发覆盖窗口 |
| `RecordingParams/Status` | 相机 id、fps、质量、includeAudio、输出路径；运行/帧数/路径/音频/错误 | 注释仍写“音频回退纯视频”，与实际实现（AAC 合流）不一致 |

### 已发现的 Java API 内部不一致

1. **音频截面口径冲突**：`CaptureApi` 类注释仍称“音频截面当前 Unsupported”，`captureFrame` 也在 `includeAudio=true` 时抛 `UnsupportedOperationException`；但 `grabAudioSection` 已首版实现并通过 dev 测试。需要明确：音频截面是独立 API，还是 `captureFrame` 的 includeAudio 也应实现/移除。
2. **连续帧**：`FrameRequest.continuous=true` 明确未实现；流式仍走 WS/MJPEG，API 层是否保留该字段需裁决。
3. **输出格式**：`FrameRequest.Format.PNG` 实现为 JPEG→BufferedImage→PNG，性能与质量语义需确认。
4. **录像不在门面**：文档称 CaptureApi 为唯一编程入口，但录像只能直接调用 `RecordingSession`（内部类路径）。
5. **错误语义**：门面混合 `IOException`、`IllegalArgumentException`、`UnsupportedOperationException`、Future 异常，未统一错误模型。
6. **线程语义**：门面注释称“调取返回 Future 完成在渲染线程”，但 `captureFrame` 明确禁止渲染线程；`grabAudioSection` 在公共线程池完成；录像 stop 可能阻塞。需要逐方法冻结。

---

## 三、WS 控制指令清单

连接：`ws://127.0.0.1:25581/`；请求 `{id, action, params}`；响应 `{id, ok, result|error}`；错误码见下。
**test-only 门控**：`TestCommandGate` 默认禁用；dev JVM 属性或 `config/captureapi/test_commands.enabled` 放行；禁用返回 `E_TEST_COMMANDS_DISABLED`。

### 相机域（正式控制面）

| action | params | 返回/说明 |
|---|---|---|
| `camera.create` | x,y,z,yaw,pitch,roll,fov,width,height,shape/projectionId,boundEntity,offsetX/Y/Z | CameraSpec；首个自动 active |
| `camera.adjust` | id + 同 create 全量 | 整体替换，保留 id/暂停态 |
| `camera.activate/pause/resume/delete` | id | CameraSpec / deleted |
| `camera.list` | 无 | active + cameras |
| `camera.status` | 无 | active/count/camera |

### 音频/录制域

| action | params | 说明/状态 |
|---|---|---|
| `audio.section` | x,y,z（默认玩家眼位）、chunks(1–100) | **test-only 门控**；返回 samples/bytes/rms/peak/start/end；W-071 首版 |
| `recording.start` | cameraId(0=active), fps(默认fps_max), jpegQuality, includeAudio, outputPath | 依赖 active 相机与 FFmpeg；返回 running/path/audioAvailable |
| `recording.stop` | 无 | 停止 + 合流；可能阻塞 |
| `recording.status` | 无 | 状态 |

### 配置/滤镜/叠加

| action | params | 说明 |
|---|---|---|
| `config.get` | key 可空 | 空=全部；未知 key→E_NOT_FOUND |
| `config.set` | key,value | 范围校验；返回 applied/persisted/message |
| `filter.list` | 无 | 滤镜列表/选中/统计 |
| `filter.set` | name | 选择 JSON 滤镜；不存在→E_NOT_FOUND |
| `filter.reload` | 无 | 重载 |
| `overlay.list` / `overlay.reload` | 无 | 贴图/层列表 |
| `overlay.show` | image,x,y,scaleX,scaleY,rotation,opacity | CPU 叠加；贴图不存在→E_NOT_FOUND |
| `overlay.clear` | 无 | 清空 |
| `renderfilter.list/reload` | 无 | 渲染名单解析摘要（**实际挂接 W-095 未实现**） |
| `audiofilter.list/reload` | 无 | 音频名单解析摘要（**实际挂接 W-095 未实现**） |

### test-only 客户端动作（`TestCommandGate`）

| action | params | 用途 |
|---|---|---|
| `client.unpause` | 无 | 关闭暂停屏/失焦暂停 |
| `client.playerPose` | 无 | 玩家位姿 |
| `client.screenshot` | 无 | 主画面截图（Screenshot） |
| `client.leaveWorld` / `client.loadWorld` | name（load 默认 s1test） | 进出世界自动化 |
| `client.playTestSound` | 无 | UI 铃铛 |
| `client.playPosSound` | dx,dy,dz,volume | 三维声源 |
| `shader.status` | 无 | 光影探测（Oculus 仅诊断） |

### 错误码

`E_UNKNOWN_ACTION / E_INVALID_JSON / E_MISSING_PARAM / E_PARAM_RANGE / E_NOT_FOUND / E_RATE_LIMIT / E_NO_PERMISSION / E_INTERNAL / E_TEST_COMMANDS_DISABLED`。

> 注意：`E_INVALID_JSON` 目前文档写“统一走 E_INTERNAL 兜底”，需要确认实际行为。

---

## 四、已移除的临时聊天命令层（历史审阅记录）

> 2026-11-02 按用户要求已从源码移除；仅保留本节作为“为什么不应把测试壳当 API”的复盘。

**原定位：测试壳，不是正式接口**。原覆盖入口与映射：

| 聊天命令域 | 底层入口 | 状态 |
|---|---|---|
| `camera ...` | `CameraManager`（直接） | 与 WS 相机域近似，但参数/默认值/反馈格式不同 |
| `frame/get_image/capture` | `CaptureApi.captureFrame` | 单帧；`/get_image` 硬编码玩家 Y+10/俯视/PNG/E盘测试目录 |
| `record ...` | `RecordingSession` | 与 WS recording 类似 |
| `audio ...` | `CaptureApi.grabAudioSection` / `AudioRingBuffer` | 与 WS audio.section 类似 |
| `config ...` | `RuntimeConfigWriter` | 与 WS config 一致 |
| `filter/overlay ...` | `FilterRegistry`/`OverlayRegistry` | 与 WS 同名域 |
| `list ...` | `RenderFilterRegistry`/`AudioFilterRegistry` | 解析摘要 |
| `status/network` | 多个内部单例 | 诊断拼装 |
| `glsl/renderlist/audiolist/multicam/overlaygpu/oculus` | 无 | 直接输出“未实现”说明 |

### 聊天命令层的问题（建议计划端明确去留）

1. **双入口重复**：WS 已有一套正式控制，聊天命令再包一套，参数语义/返回文案/错误处理均不同；
2. **硬编码测试资产**：`E:\捕获接口\testcases\commands`、玩家 Y+10、pitch=90、PNG；
3. **缺少状态模型**：相机 id、active、录像/音频状态各自为政，无统一会话；
4. **未实现项混入同一命名空间**：靠输入后打印文案，不是契约级 `Unsupported`/能力查询；
5. **未运行验证**：仅编译/构建/安装；用户尚未实测，不能作为 API 质量证据。

**建议**：聊天命令保持“非产品”定位，或整体移入 debug 包并按 `TestCommandGate` 门控；正式 API 只保留 Java 门面 + WS 控制，并统一契约。

---

## 五、能力矩阵（与接口相关）

| 能力 | 状态 | 证据/缺口 |
|---|---|---|
| 世界内单帧/流式/MJPEG | 已实现并 PASS | S1/S3 |
| 相机创建/调整/暂停/删除/列表 | 已实现 | dev 自动化 |
| 音频 PCM / 时间戳 | 已实现并 PASS | S2.2/S2.3 |
| 监听器方向性 | 左右/翻转 PASS | 用户听辨未做；取舍未裁决 |
| 音频截面 | 首版实现 | dev chunks=5/10；W-071 语义未冻结 |
| 录像视频+音频 | 已实现 | 1–2s dev PASS；≥4h 未验 |
| 配置运行时读写 | 已实现 | 参数范围/持久化策略需审 |
| JSON 滤镜 / CPU 叠加 | 已实现 | 名单实际挂接未实现 |
| 渲染/音频名单 | 仅解析/摘要 | W-095 未挂接 |
| 多路独立渲染 | 未实现 | P2.3，当前单 active 相机 |
| GPU 叠加 | 未实现 | P2.4，CPU 路径可用 |
| GLSL 滤镜 | 未做 | P2.1 按裁剪条件 |
| Oculus 离屏适配 | 未实装 | W-043，仅探测 |
| 远程 MP / 专用服 | 已实现并生产 PASS | 含多玩家/权限/限额 |
| 录像/音频测试专用命令 | 已门控 WS；聊天命令未门控 | 发布前需处理 |

---

## 六、文档一致性检查（需计划端确认）

| 文档 | 现状/偏差 |
|---|---|
| `docs/控制指令schema.md` | 只覆盖相机域；仍写“fps_max 未开放、音频/录像等无指令”，与当前 33 action 不符 |
| `docs/JavaAPI门面.md` | 已部分更新 P1.2/P1.4，但仍需逐方法核对线程/错误/录像入口 |
| `docs/录像功能.md` / `RecordingParams` 注释 | 录像音频注释仍写“回退纯视频”，与 AAC 合流实现不一致 |
| `CaptureApi` 类注释 | 仍称音频截面 Unsupported，与实现冲突 |
| ~~`docs/临时测试指令表.md`~~ | **已移除**（随临时壳一起删除） |
| `docs/网络推流协议.md` / `HTTP视频接口说明.md` | 需核对最新字段与错误码 |

---

## 七、建议计划端裁决的问题（按优先级）

1. **正式接口边界**：产品只暴露 Java API？WS 控制是否正式？聊天命令是否只作为 debug 工具？
2. **Camera 模型**：单 active 是否足够？多相机/多路渲染是否在范围内？坐标/朝向/绑定实体语义是否冻结？
3. **Frame API**：`continuous`、`includeAudio`、PNG、HTTP 缓存窗口、`timeoutMillis`、渲染线程约束，逐项冻结或删除字段。
4. **Audio API**：`grabAudioSection` 虚拟位置朝向、快照并发、chunk 数量/时长语义、与流式消费的互斥域。
5. **Recording**：正式入口是否进 `CaptureApi`；停止阻塞、音轨合流、长录稳定性、FFmpeg 依赖如何暴露。
6. **错误/能力契约**：统一异常/错误码；未实现能力用能力查询或 `Unsupported`，不要靠聊天文案。
7. **测试命令治理**：现有 WS test-only 已门控；聊天命令集要么删除，要么迁入 debug 并同门控。
8. **文档基线**：以本清单为输入，更新 `控制指令schema/JavaAPI门面/录像/网络` 四份主文档，再冻结 v0.6 契约。

---

## 八、给计划端的建议审阅顺序

1. 先看本清单 + `docs/JavaAPI门面.md` + `docs/控制指令schema.md`；
2. 裁决第七节 1~3（接口边界/相机/帧），这三项决定后续 API 重排；
3. 再裁决音频截面/录像/错误模型/测试命令；
4. 冻结后由开发端按新契约改造，移除临时聊天命令或迁入 debug，并重新跑 build.ps1 24/24、S1/S3/S4 自动验证与专用服主链路。
