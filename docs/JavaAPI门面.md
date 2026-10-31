# 模组内 Java API 门面（T4.7）

> 状态：**I4.7.1 门面、I4.7.2 grabFrame、I4.7.4 一致性方案完成**（2026-11-01）；**I4.7.3 音频截面待 W-071 语义冻结**（当前明确 `UnsupportedOperationException`，不做假实现）；世界内调取/一致性对拍待测试分计划。
> 关联：I4.7.1~4、T2.2.4、T3.3、I5.2.3 对接文档。

## 一、门面（`com.captureapi.net.CaptureApi`，静态方法）

| 方法 | 线程语义 | 返回/异常 |
|---|---|---|
| `latestFrame()` | 任意线程，只读 | 最新 `JpegFrame` 或 null（与 WS/HTTP 同源 `VideoFrameBus`） |
| `grabFrame(CameraCreateParams, timeoutMs)` | 任意线程提交，Future 在渲染线程完成 | 临时相机渲一帧的 `CompletableFuture<JpegFrame>`；世界未就绪/超时/渲染冲突异常完成 |
| `latestAudioChunk()` | 任意线程，非消费只读 | 最近 `AudioChunk` 或 null（`AudioRingBuffer.peekLatest`） |
| `grabAudioSection(x,y,z,chunkCount)` | 任意线程 | **当前明确失败**：W-071 未冻结，避免假返回；参数范围校验 [1,100] 保留 |
| `createCamera/adjustCamera/activateCamera/pauseCamera/resumeCamera/deleteCamera/listCameras/activeCameraId` | 任意线程（CameraManager 线程安全） | 直接代理 CameraManager |

同源约束：门面不缓存、不加工；画面与网络出口读同一 `VideoFrameBus` 实例，音频读同一环形缓冲，相机管理同一 `CameraManager`。

## 二、实时调取（I4.7.2）

`grabFrame` → `VideoStreamHub.requestOneShot`（T2.2.4 临时相机路径）→ 与流式同帧互斥 → `JpegFrame` Future。临时相机 id=0、不进 CameraManager、不占自增 id；状态/相机/主手由管线恢复。

## 三、音频截面（I4.7.3，挂起）

- 目标语义：虚拟位监听器移动→采集 1~100 拍→恢复；与视频调取成对；
- 当前状态：**未实现**（W-071 待用户确认/Spike）；API 明确抛 `UnsupportedOperationException`，不返回伪造数据；
- 实现所需：监听器同帧移动（T3.3 已有机制）+ 环形缓冲读端串行化/独占窗口 + 与流式推流读端协调（T4.6 互斥域扩展）。

## 四、一致性（I4.7.4）

- 结构核验：门面、WS 推流、HTTP 单帧/ MJPEG 三处均引用同一 `VideoFrameBus` / `AudioRingBuffer`（代码评审基线）；
- 运行时对拍方案：主世界内创建 active 相机 → 进程内 `CaptureApi.latestFrame()` 与 HTTP `/camera/{id}/frame` 按 `X-Frame-Timestamp` 配对 → JPEG 逐字节比对；音频 `latestAudioChunk()` 与 WS 二进制块按 start/end 配对 → PCM 逐字节比对；
- 脚本归 I7.1.4 回归集（待测试分计划）；
- 时序边界：按时间戳配对，不要求同时刻读取。

## 五、已知缺口

- 音频截面未实现（W-071）；
- `grabFrame` 在无世界/主菜单下会异常完成（预期）；世界内真实调取与主画面零扰动未测；
- 一致性对拍脚本/Java API 示例未写（I7.1.4 后续）；
- 门面版本标注 v0.5，未来扩展走新增方法。
