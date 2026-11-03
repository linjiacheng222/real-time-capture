# 缺陷1/2/3 修复报告（S9）

> 日期：2026-11-04 00:17 CST  
> 工程：`E:\捕获接口`  
> 修复提交：`5abdee8`（代码提交；本报告随后单独提交）  
> 构建：`scripts/build.ps1` **24/24 PASS**；magic audit **0**；jar `395127 B`  
> 说明：未做用户视觉/听觉长跑验收；以下为静态检查、自动 smoke 与游戏内 dev 实测。

---

## 缺陷1（P0）单机相机区块强加载未实现

### 根因

- `CaptureChunkPlanner` 只计算视锥/声音范围区块集合；
- 单机时 `RemoteCaptureClient` 在 `hasSingleplayerServer()` 分支直接 no-op；
- 因此相机水平朝向的远处目标区块没有被 ticket 强加载，画面只剩已加载/天空。

### 修复

新增：

- `src/main/java/com/captureapi/video/chunk/SingleplayerTicketHolder.java`
  - `Minecraft.getInstance().getSingleplayerServer() != null` 时启用；
  - `TicketType<ChunkPos> CAPTURE_TICKET = TicketType.create("captureapi_camera", ...)`；
  - `ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)`；
  - 渲染线程只做 diff，`server.execute(...)` 投递到集成服线程执行
    `addRegionTicket/removeRegionTicket`；
  - 上限 `SINGLEPLAYER_MAX_CHUNK_TICKETS = 2048`，超出截断并 WARN；
  - `releaseAll()` 世界退出释放全部。

接入：

- `CaptureChunkPlanner.capture()` 末尾：`SingleplayerTicketHolder.update(chunks)`；
- `WorldLifecycleHandler` 退出路径：`SingleplayerTicketHolder.releaseAll()`。

### 静态/运行验证

游戏内 dev 实测（quickPlaySingleplayer，camera1 active）：

```text
[CHUNK/TICKET] 单机相机强加载启用：新增=297，held=297
[CHUNK/TICKET] 释放单机 ticket=297
```

- `server.execute` 日志线程为 `Server thread`，确认 ticket 操作在服务端线程；
- 退出世界后释放 297 个 ticket；
- 2048 上限为静态代码守卫，本次仅生成 297 个区块，未压满上限。

---

## 缺陷2（P0）录像音频 audioChunks=0

### 定位过程

按你的要求先在 `RecordingSession.audioLoop()` 的 `chunk == null` 分支增加：

- `audioNullPolls` 计数；
- 首次/每 100 次空拍打印：`channel / primary / ring.writtenPolls / occupancy / slotCount / sealed`。

同时检查原始取数：`audioChannelId=-1 → snapshotChannel(primaryChannelId(),1)`。
默认配置 `audio_multi_listener_enabled=false` 时 primary=0，正常走 `AudioRingBuffer.snapshotRecent(1)`。

### 发现的两个问题

1. **WS `recording.start` 只认 `includeAudio`，不认 `audio`**
   - 用户侧常用 `{"camera":1,"audio":true}`；
   - 旧代码 `includeAudio=false` → 不启动 audioLoop → `audioChunks=0`、无音轨；
   - 已在 `ControlCommandDispatcher.recordingStart` 增加 `audio` 别名。
2. **snapshot 空拍兜底缺失**
   - `AudioRingBuffer.peekLatest()` 原来依赖 `writeIndex != readIndex`，WS 消费端读空后返回 null；
   - 增加 `lastWritten` 缓存，使 `peekLatest()` 在消费后仍能返回最近一拍；
   - `RecordingSession.readRecordingChunk()`：snapshot 为空时回退 `peekLatest`，并记录回退日志；
   - 仍不推进消费游标，避免抢 WS 音频。

### 验证

默认配置（`audio_multi_listener_enabled=false`）：

1. 12 秒录像（`includeAudio:true`，camera=1）：
   - `[REC/STOP] frames=193，audioChunks=553，audioAvailable=true`
   - FFmpeg 合流完成 553 拍；产物 `fix_audio_test.mp4` 168748 B。
2. 5 秒录像（故意使用 `audio:true` 别名，camera=1）：
   - `[REC/STOP] frames=81，audioChunks=238，audioAvailable=true`
   - 产物 `fix_audio_alias.mp4` 115997 B；
   - ffmpeg 流探测：
     ```text
     Stream #0:0 Video: h264 ...
     Stream #0:1 Audio: aac (LC), 44100 Hz, stereo
     ```
3. 两次测试均未见 `[REC/AUDIO] 空拍` 日志；兜底分支保留用于异常时防 0 拍。

> 注：12 秒测试中 WS `recording.stop` 客户端等待超时，但服务端最终完成 `REC/STOP`/FFmpeg 合流（日志完整），文件有效；这是 WS 连接队列/长请求表现，不是音频取数失败。

---

## 缺陷3（P1）max_cameras 上限绕过

### 根因

`CameraManager` 的相机集合跨世界未清空；退出世界后相机仍保留，下一世界继续创建导致 `camera.list` 出现 3 台，破坏 `max_cameras` 上限语义。

### 修复

`WorldLifecycleHandler` 的 `LoggingOut / LevelUnload / tick 兜底` 退出任务统一执行：

1. `SingleplayerTicketHolder.releaseAll()`；
2. `CameraManager.get().clear()`；
3. `WorldResourceManager.onWorldLeave()`。

`CameraManager.clear()` 已有，无需补 `clearAll`。

### 验证

游戏内实测，`max_cameras=2`：

1. 创建 camera1、camera2：成功；
2. 创建 camera3：
   ```text
   E_CAMERA_LIMIT: max_cameras=2，当前相机数=2
   ```
3. `client.leaveWorld` 后：
   - `camera.list` → `{"active":0,"cameras":[]}`
   - `[CHUNK/TICKET] 释放单机 ticket=297`
4. `client.loadWorld` 后：
   - `camera.list` → 空；
   - 新建相机成功，计数从 0 重新开始（id 继续递增），不再出现 3 台残留。

---

## 改动文件清单

| 文件 | 改动 |
|---|---|
| `src/main/java/com/captureapi/video/chunk/SingleplayerTicketHolder.java` | 新增单机 ticket 持有者/diff/释放/2048 上限 |
| `src/main/java/com/captureapi/video/chunk/CaptureChunkPlanner.java` | 求取后调用 `SingleplayerTicketHolder.update(chunks)` |
| `src/main/java/com/captureapi/core/resource/WorldLifecycleHandler.java` | 退出路径释放 ticket + 清空 CameraManager |
| `src/main/java/com/captureapi/core/constant/CaptureConstants.java` | `SINGLEPLAYER_MAX_CHUNK_TICKETS=2048` |
| `src/main/java/com/captureapi/audio/AudioRingBuffer.java` | `lastWritten` 缓存；`peekLatest` 消费后仍可读 |
| `src/main/java/com/captureapi/net/recording/RecordingSession.java` | 空拍计数日志；snapshot→peekLatest 兜底 |
| `src/main/java/com/captureapi/net/control/ControlCommandDispatcher.java` | `recording.start` 支持 `audio:true` 别名 |
| `docs/evidence/S6/magic-audit.txt` | 构建审计刷新 |

### 关键 diff 摘要

```diff
+ // SingleplayerTicketHolder
+ TicketType.create("captureapi_camera", chunkComparator)
+ ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)
+ server.execute(() -> apply(server, dimension, addCopy, removeCopy))
+ CAPTURE_TICKET addRegionTicket/removeRegionTicket
+ SINGLEPLAYER_MAX_CHUNK_TICKETS = 2048

+ // CaptureChunkPlanner.capture
+ SingleplayerTicketHolder.update(chunks);

+ // WorldLifecycleHandler leave task
+ SingleplayerTicketHolder.releaseAll();
+ CameraManager.get().clear();
+ manager.onWorldLeave();

+ // RecordingSession
+ AudioChunk chunk = readRecordingChunk();
+ if (chunk == null) { nullCount++; ... ring state log ... }
+ if (snapshot == null) chunk = AudioRingBuffer.get().peekLatest();

+ // ControlCommandDispatcher
+ else if (params.has("audio")) includeAudio = params.get("audio").getAsBoolean();

+ // AudioRingBuffer
+ lastWritten = new AudioChunk(startNanos, endNanos, sampleCount, pcm);
+ peekLatest() { return lastWritten; }
```

---

## 构建与提交

- `E:\捕获接口\scripts\build.ps1`（JAVA_HOME=`E:\Tools\jdk17`）：
  - **24/24 PASS**
  - 日志：`E:\捕获接口\build\reports\env\build-20261104-001704.log`
- magic audit：`literals=0, files=0`
- 产物：`E:\捕获接口\build\libs\captureapi-0.5.0.jar`（395127 B）
- 代码修复 commit：`5abdee8`
  - 提交信息：`fix:【缺陷1/2/3修复】单机相机强加载/录像音频/max_cameras 跨世界残留`
- 本报告为后续 docs commit；最终报告提交 hash 以 `git log` 为准。

## 未做/注意

- 单机 ticket 2048 上限未做压满实测（仅静态守卫 + 297 个运行时）；
- 相机跨维度（主世界/下界）未专门验证 ticket 按维度释放；
- 录像 12s 测试中 `recording.stop` 的 WS 客户端等待超时，服务端随后完成合流；不影响音频取数结论；
- 未做用户视觉/听觉验收、长跑、Oculus。
