# 模组内 Java API 门面（T4.7）

> 2026-11-03：新增多 listener 音频 API，见 `docs/多监听器音频架构.md` 与 `docs/API使用说明.md` 第九点六节。

> 2026-11-02 修订提示：统一 `capture(FrameRequest)` 已新增；本文旧表未完全覆盖，完整用法以 `docs/API使用说明.md` 为准。

> 状态：**I4.7.1 门面、I4.7.2 grabFrame、I4.7.3 音频截面首版、I4.7.4 一致性方案完成**（2026-11-01）；音频截面虚拟位置/快照已实现，世界内真实位置听感与长时对拍待测试分计划。
> 关联：I4.7.1~4、T2.2.4、T3.3、I5.2.3 对接文档。

## 一、门面（`com.captureapi.net.CaptureApi`，静态方法）

| 方法 | 线程语义 | 返回/异常 |
|---|---|---|
| `latestFrame()` | 任意线程，只读 | 最新 `JpegFrame` 或 null（与 WS/HTTP 同源 `VideoFrameBus`） |
| `grabFrame(CameraCreateParams, timeoutMs)` | 任意线程提交，Future 在渲染线程完成 | 临时相机渲一帧的 `CompletableFuture<JpegFrame>`；世界未就绪/超时/渲染冲突异常完成 |
| `latestAudioChunk()` | 任意线程，非消费只读 | 最近 `AudioChunk` 或 null（`AudioRingBuffer.peekLatest`） |
| `grabAudioSection(x,y,z,chunkCount)` | 任意线程 | 虚拟 listener→等待 N 拍→环形缓冲快照→恢复；异步 Future，参数 [1,100]；首版快照不推进消费游标 |
| `createCamera/adjustCamera/activateCamera/pauseCamera/resumeCamera/deleteCamera/listCameras/activeCameraId` | 任意线程（CameraManager 线程安全） | 直接代理 CameraManager |

同源约束：门面不缓存、不加工；画面与网络出口读同一 `VideoFrameBus` 实例，音频读同一环形缓冲，相机管理同一 `CameraManager`。

## 二、实时调取（I4.7.2）

`grabFrame` → `VideoStreamHub.requestOneShot`（T2.2.4 临时相机路径）→ 与流式同帧互斥 → `JpegFrame` Future。临时相机 id=0、不进 CameraManager、不占自增 id；状态/相机/主手由管线恢复。

## 三、音频截面（I4.7.3，首版已实现）

- 语义：采集线程 listener 临时切到虚拟位置（朝向沿用玩家）→ 等待 chunkCount 拍 → 从环形缓冲快照最近 N 拍 → Future 返回合并 AudioChunk；
- 首版证据：chunks=5 返回 2205 采样/8820B，chunks=10 返回 4410 采样/17640B，Rms/Peak 非零；
- 限制：快照按物理槽回看，极端并发覆盖窗口存在；真实虚拟位置听感/与流式消费协调仍待测试；WS 测试指令 `audio.section` 为调试辅助。

## 四、一致性（I4.7.4）

- 结构核验：门面、WS 推流、HTTP 单帧/ MJPEG 三处均引用同一 `VideoFrameBus` / `AudioRingBuffer`（代码评审基线）；
- 运行时对拍方案：主世界内创建 active 相机 → 进程内 `CaptureApi.latestFrame()` 与 HTTP `/camera/{id}/frame` 按 `X-Frame-Timestamp` 配对 → JPEG 逐字节比对；音频 `latestAudioChunk()` 与 WS 二进制块按 start/end 配对 → PCM 逐字节比对；
- 脚本归 I7.1.4 回归集（待测试分计划）；
- 时序边界：按时间戳配对，不要求同时刻读取。

## 五、已知缺口

- `grabFrame` 在无世界/主菜单下会异常完成（预期）；世界内真实调取与主画面零扰动未测；
- 一致性对拍脚本/Java API 示例未写（I7.1.4 后续）；
- 门面版本标注 v0.5，未来扩展走新增方法。

## 六、多 listener 音频（2026-11-03）

- `CaptureApi.audioChannels()`：player/camera/virtual 通道信息；
- `CaptureApi.latestAudioChunk(id)`：0=player，>0=camera，-1=virtual；
- `CaptureApi.primaryAudioChannelId()` / `multiListenerEnabled()`；
- `grabAudioSection` feature 开启时走 virtual channel；
- 详见 `docs/多监听器音频架构.md`。
