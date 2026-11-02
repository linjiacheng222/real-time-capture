# CaptureAPI 完整 API 使用说明（v0.5 第二阶段）

> 基准代码：2026-11-02 计划端整肃后的 `main`（HEAD `6782b9a`）。本说明以**实际代码**为准；计划端早先的 `docs/API全集与测试指令.md` 部分内容已过时（见文末差异表）。
> 构建状态：`build.ps1` 24/24 PASS；magic-number audit 0（本轮修复计划端新增代码的数字白名单回归）。
> dev 运行验证（2026-11-02）：WS `capture` 统一入口实测成功——PNG 1,489,357B；`audio:true,audioChunks:5` 返回 sampleCount=2205 + PCM；证据 `docs/evidence/S6/api-capture-websocket.json`。游戏内 `/captureapi` 仅确认注册日志，完整命令未系统实测。
> 重要边界：本文描述能力≠最终验收；S2 听辨、≥4h 长跑、Oculus 实测仍未完成，S6 仍为条件放行。

---

## 一、接口总览与推荐使用方式

| 接口 | 入口 | 适用场景 | 状态 |
|---|---|---|---|
| Java API | `com.captureapi.net.CaptureApi` | 模组内二次开发 | 核心可用；新增 `capture()` 统一入口 |
| WS 控制 | `ws://127.0.0.1:25581/` | 自动化/外部控制/取帧+音频 | 33→34 个 action（新增 `capture`） |
| HTTP | `http://127.0.0.1:25580` | 健康、单帧、MJPEG | 可用 |
| WS 二进制流 | 同一 WS 连接 | 视频 JPEG、音频 PCM 实时推流 | 可用 |
| 游戏内命令 | `/captureapi` | 玩家手动测试 | v2 函数式；已确认注册日志，完整参数未系统实测 |
| M9 专用服 | 独立 server jar | 远程 MP 区块申请 | 生产主链路已验 |
| 测试专用指令 | `client.*` / `audio.section` / `shader.status` | 自动化测试 | 默认门控关闭 |

**推荐路径**：
- 程序取单帧＋可选音频：**优先 Java `CaptureApi.capture(FrameRequest)` 或 WS `capture`**；
- 只要最新帧：`latestFrame()` / HTTP `/camera/0/frame`；
- 连续流：MJPEG 或 WS 二进制；
- 专门录像带音轨：WS `recording.*` 或游戏 `record`；
- 游戏内人工验证：`/captureapi capture ...`。

**核心概念**：
- 相机 id `0` 表示当前 active 相机；
- 帧/音频时间戳均为 `CaptureClock` 纳秒（唯一时钟源）；
- 帧总线为最新帧缓存（drop old keep latest），不是逐帧队列；
- 离屏渲染需要“已进入世界 + active 相机”。

---

## 二、快速开始（5 分钟）

### 1. 健康检查
```bash
curl http://127.0.0.1:25580/health
```
关键字段：`ok/running/http/ws/ws_port/connections/max_connections/video_frames_published/audio_ring_written/pipeline_failed_frames/shader_backend/remote_ready` 等（完整字段见表 4.1）。

### 2. 创建相机（WS）
```json
{"id":"c1","action":"camera.create","params":{"x":0,"y":80,"z":0,"yaw":0,"pitch":30,"fov":70}}
```

### 3. 取一张图（WS 统一 capture；dev 已实测）
```json
{"id":"cap1","action":"capture","params":{
  "pos":[0,80,0],"rot":[0,90],"fov":70,
  "temporary":true,"format":"PNG",
  "out":"E:\\captures\\top.png"
}}
```
返回 `result.location` 为实际文件路径（若重名会自动加后缀）。

### 4. 实时视频流（MJPEG）
```bash
curl -o - http://127.0.0.1:25580/camera/0/stream --max-time 10
# 或浏览器直接打开
```
FFmpeg 消费：
```bash
ffmpeg -y -i http://127.0.0.1:25580/camera/0/stream -t 10 -c copy test.mkv
```

### 5. 录像（含音频）
```json
{"id":"r1","action":"recording.start","params":{"cameraId":0,"fps":30,"includeAudio":true}}
```
停止：
```json
{"id":"r2","action":"recording.stop"}
```

### 6. 游戏内命令
```
/captureapi help
/captureapi capture pos:0,90,0 rot:0,90 fov:70 out:E:\captures\top.png
```
游戏命令参数语法见第五节（key:value 空格分隔）。

---

## 三、统一帧获取模型（Java / WS / 游戏命令共用）

计划端新增的统一入口：

```java
CaptureApi.CaptureOutcome outcome = CaptureApi.capture(FrameRequest request);
FrameResult frame = outcome.frame();
AudioChunk audio = outcome.audio(); // 仅 request.includeAudio=true 时
```

`FrameRequest` 关键字段（Builder）：

| 字段/方法 | 含义 | 注意 |
|---|---|---|
| `position(x,y,z)` | 临时相机绝对位置 | 与 `boundEntity` 互斥 |
| `boundEntity(uuid)` | 绑定实体 | 与 position 互斥 |
| `rotation(yaw,pitch,roll)` | 相机朝向，度，pitch 正向下 | 范围由服务端/相机校验 |
| `fov(deg)` | 视场角 | (1,179) |
| `resolution(w,h)` | 输出分辨率 | 省略用 active/全局 |
| `quality(q)` | JPEG 质量 | 已实现：captureFrame 在请求质量与全局不同时重编码；dev 实测 q10=45,202B/q90=331,068B |
| `format(JPEG/PNG)` | 输出格式 | PNG 为 JPEG 解码后转 PNG |
| `outputMethod(FILE/HTTP)` | 文件或 HTTP URL | HTTP 需网络服务运行 |
| `outputPath(Path)` | 文件路径 | 省略则写 `recording_output_dir/capture_<ts>.jpg|png` |
| `temporary(bool)` | 强制临时渲染 | 与 continuous 互斥 |
| `continuous(bool)` | 连续帧 | **未实现，抛 UnsupportedOperationException** |
| `timeoutMillis(ms)` | 等待超时 | >0，默认 5000 |
| `filter(String)` | 滤镜覆盖：null=不改；""=关闭；名称=选中 | **全局状态副作用** |
| `overlay(OverlayModel)` / `overlays(List)` | 叠加覆盖 | **全局状态副作用** |
| `audio(boolean,chunks)` | 附带音频截面 | 仅 `CaptureApi.capture()` 生效；直接 `captureFrame()` 忽略 |
| `filter/overlays` 在 `captureFrame` 内应用 | 选择/清空全局滤镜、叠加 | 不会自动恢复 |

**滤镜/叠加作用域**：`captureFrame` 会快照滤镜/叠加全局状态，`request.filter/overlays` 覆盖仅在本帧生效，结束后恢复（由 `CAPTURE_LOCK` 串行化）。

**音频语义**：
- `capture()` 先取帧，再按 `request.toCameraParams(active)` 的位置采集 `audioChunks` 拍；
- 显式 `position/boundEntity` 优先；无显式位置时使用 active 相机；两者都没有时为 `(0,0,0)`；
- 音频失败只 WARN，`CaptureOutcome.audio()==null`，不影响帧；
- 直接 `captureFrame()` 不执行音频（`includeAudio` 被忽略）。

**线程规则**：
- `captureFrame` / `capture`：**不可在渲染线程调用**；会阻塞等待渲染线程完成；
- `grabFrame`：异步 Future，完成在渲染线程；
- `latestFrame`：任意线程只读；
- 相机管理：任意线程；
- 音频截面：异步 Future，公共线程池完成；
- 录像 `RecordingSession.stop()` 可能阻塞（FFmpeg 合流），不要放渲染线程。

---

## 四、HTTP 接口

### 4.1 `/health`（免鉴权）

```
GET http://127.0.0.1:25580/health
```

实际主要字段（以代码为准）：

| 字段 | 含义 |
|---|---|
| `ok/running/http/ws` | 服务状态 |
| `ws_port` | WS 端口（通常 `net_port+1`） |
| `bind/lan_enabled` | 绑定/局域网 |
| `connections/max_connections/max_qps` | 连接/限流 |
| `rejected_connections/errors/uptime_ms` | 错误/运行时长 |
| `token_required` | 是否开启 token |
| `slow_disconnects/http_slow_disconnects/http_streams` | 背压/流 |
| `video_frames_published` | 累计发布帧 |
| `game_fps` | 主循环 FPS |
| `offscreen_rendered_frames/readback_frames/encoded_frames/encode_dropped_frames/encode_failed_frames` | 管线计数 |
| `pipeline_failed_frames` | 离屏失败帧 |
| `audio_ring_written/dropped/silent` | 音频环计数 |
| `shader_backend/shader_detected/shader_active` | 光影探测（Oculus 未适配） |
| `remote_ready/remote_requests/remote_accepted/remote_last_code` | M9 远程申请 |
| `multicam_cached_cameras/multicam_interval_ms` | P2.3 v1 多相机缓存/时间片间隔 |
| `sync_delta_ms_avg/sync_delta_samples/sync_regressions/sync_warnings` | 同步监测 |
| `render_backpressure` | 固定为 `drop_old_keep_latest` |

### 4.2 单帧

```
GET /camera/{id}/frame
```
- `id=0` = active；
- `id` = active：读 `VideoFrameBus.latest()`；
- `id` = 非 active 相机：读 `CameraFrameStore`（多相机时间片 v2，约 250ms/帧；独立 FBO/PBO/编码器）；
- 不存在的相机 → `404 E_CAMERA_NOT_FOUND`；
- 相机存在但暂无缓存帧 → `204`（单帧）/心跳（流）；
- 无 active 且 id=0 → `204`（单帧）/`404 E_NO_ACTIVE_CAMERA`（流）；
- 成功：`200 image/jpeg` + `X-Frame-Timestamp/Width/Height`；
- 限速：超过 `max_qps` → `429 E_RATE_LIMIT`。

### 4.3 MJPEG（多相机 v1）

```
GET /camera/{id}/stream
```
- `Content-Type: multipart/x-mixed-replace; boundary=captureapi-frame-boundary`；
- 每帧附带 `X-Frame-Timestamp`；
- 无帧时写心跳空行；
- 并发流上限：超过 `max_connections` → `503 E_CONNECTION_LIMIT`；
- 无 active 且 id=0 → `404 E_NO_ACTIVE_CAMERA`；
- 非 active id 使用 `CameraFrameStore` 时间片帧（约 250ms/帧），不是连续 30fps。

### 4.4 鉴权

- 默认 token 关闭；
- 开启后：HTTP 头 `X-CaptureAPI-Token: <token>` 或 `/health` 免鉴权；
- WS：握手 query `?token=...`；
- 端口/token 设置需重启。

---

## 五、WS 控制接口

### 5.1 连接与信封

```
ws://127.0.0.1:25581/
```

```json
{"id":"任意配对id","action":"动作名","params":{...}}
```
成功：
```json
{"id":"...","ok":true,"result":{...}}
```
失败：
```json
{"id":"...","ok":false,"error":{"code":"E_XXX","message":"..."}}
```

错误码：`E_UNKNOWN_ACTION` / `E_INVALID_JSON` / `E_MISSING_PARAM` / `E_PARAM_RANGE` / `E_NOT_FOUND` / `E_RATE_LIMIT` / `E_NO_PERMISSION` / `E_INTERNAL` / `E_TEST_COMMANDS_DISABLED`。
> 代码中不存在 `E_CAMERA_LIMIT`、`E_NOT_IMPLEMENTED`；相机上限会以 `E_RATE_LIMIT`＋消息返回。

### 5.2 统一 `capture`（新增核心）

```json
{"id":"cap","action":"capture","params":{
  "pos":[x,y,z], "rot":[yaw,pitch,roll?], "fov":70,
  "res":[1280,1024], "quality":85,
  "filter":"grayscale",
  "overlay":{"image":"watermark","x":0.9,"y":0.1,"scaleX":0.2,"scaleY":0.2,"rotation":0,"opacity":0.8},
  "overlays":[ ... ],
  "out":"E:\\capture\\frame.png",
  "method":"FILE", "format":"PNG",
  "audio":true, "audioChunks":5,
  "bound":"uuid", "temporary":true, "timeout":5000
}}
```

| 参数 | 类型 | 说明 |
|---|---|---|
| `pos` | `[x,y,z]` | 显式位置；与 `bound` 互斥 |
| `rot` | `[yaw,pitch,(roll)]` | 度；pitch 正向下 |
| `fov` | number | (1,179) |
| `res` | `[w,h]` | 分辨率 |
| `quality` | int | 请求级 JPEG 质量；与全局不同时重编码 |
| `filter` | string | 滤镜覆盖；`""` 关闭 |
| `overlay` / `overlays` | object/array | 替换叠加层；全局副作用 |
| `out` | string | FILE 输出路径 |
| `method` | `FILE/HTTP` | HTTP 时不写文件，返回 URL |
| `format` | `JPEG/PNG` | 默认 JPEG |
| `audio` | bool | 附带音频截面；返回 `audio.pcmBase64` |
| `audioChunks` | int | 默认 1；实际音频来自 active 相机位置 |
| `bound` | UUID | 绑定实体；与 pos 互斥 |
| `temporary` | bool | 强制临时相机渲染；**无 explicit pos 时建议 true** |
| `timeout` | ms | 默认 5000 |

返回 `result`：`outputMethod/location/timestampNanos/width/height/sizeBytes`；有音频时附加 `audio.sampleCount/startNanos/endNanos/pcmBase64`。

> 注意：若 active 存在且请求既无 `pos/rot` 也无 `temporary:true`，取的是**最新缓存帧**（不是新渲染），滤镜/叠加对已缓存帧可能不生效；要新画面请给 `pos/rot` 或 `temporary:true`。
>
> dev 实测（2026-11-02）：显式 pos/rot + temporary + PNG 成功；audio:true/audioChunks:5 返回 `sampleCount=2205` 的 PCM（Base64），证据 `docs/evidence/S6/api-capture-websocket.json`。
> quality 覆盖实测 q10=45,202B / q90=331,068B；滤镜 capture 后已恢复，证据 `docs/evidence/S6/api-quality-filter.json`。

### 5.3 相机

| action | params | 返回 |
|---|---|---|
| `camera.create` | `x,y,z,yaw,pitch,roll,fov,width,height,shape,boundEntity,offsetX/Y/Z` | CameraSpec |
| `camera.adjust` | `id` + 同 create（整体替换） | CameraSpec |
| `camera.activate`/`pause`/`resume`/`delete` | `id` | 状态 |
| `camera.list` | 无 | `{active,cameras}` |
| `camera.status` | 无 | `{active,count,camera?}` |

### 5.4 参数 / 滤镜 / 叠加 / 名单

| action | params | 说明 |
|---|---|---|
| `config.get` | `key` 可空 | 空=全部；未知 key→E_NOT_FOUND |
| `config.set` | `key,value` | 范围校验；返回 applied/persisted/message |
| `filter.list` | 无 | 列表/当前选中/统计 |
| `filter.set` | `name`（空=关闭） | 不存在→E_NOT_FOUND |
| `filter.reload` | 无 | 重载 |
| `overlay.list` / `overlay.reload` | 无 | 贴图/层 |
| `overlay.show` | `image,x,y,scaleX,scaleY,rotation,opacity` | CPU 叠加 |
| `overlay.clear` | 无 | 清空 |
| `renderfilter.list/reload` | 无 | 解析摘要；**离屏实体渲染已挂接**（方块/粒子未覆盖） |
| `audiofilter.list/reload` | 无 | 解析摘要；**SoundEngine.play 全局过滤已挂接**（含主画面音频） |

### 5.5 录像

| action | params | 说明 |
|---|---|---|
| `recording.start` | `cameraId(0=active)`、`fps`、`jpegQuality`、`includeAudio`、`outputPath` | 需 active 相机 + FFmpeg |
| `recording.stop` | 无 | 停止；可能阻塞 |
| `recording.status` | 无 | running/frames/path/audioAvailable/error |

> `jpegQuality` 已实现：与全局不同时对每帧重编码（增加 CPU）；默认取全局 jpeg_quality；`fps` 仅声明 FFmpeg 输入帧率，实际节奏由帧总线决定。`includeAudio` 已实现（临时 PCM + 停止后 AAC 合流），与计划端旧文档“未实现”不同。

### 5.6 音频截面 / 光影 / 测试指令

| action | params | 说明 |
|---|---|---|
| `audio.section` | `x,y,z`（可选，默认玩家眼位）、`chunks`(1–100) | **test-only**；返回 start/end/samples/bytes/rms/peak，不返回 PCM |
| `shader.status` | 无 | **test-only**；backend/detected/active（Oculus 仅探测） |
| `client.unpause` | 无 | test-only |
| `client.playerPose` | 无 | test-only |
| `client.screenshot` | 无 | test-only |
| `client.leaveWorld` | 无 | test-only |
| `client.loadWorld` | `name`（默认 s1test） | test-only |
| `client.playTestSound` | 无 | test-only |
| `client.playPosSound` | `dx,dy,dz,volume` | test-only |
| `shader.status` | 无 | test-only |

**测试门控**：`TestCommandGate` 默认禁用；dev JVM 属性 `captureapi.debug.selftest`/`captureapi.debug.test_commands` 或文件 `<gameDir>/config/captureapi/test_commands.enabled` 放行；禁用返回 `E_TEST_COMMANDS_DISABLED`。

---

## 六、WS 二进制推流

连接后**自动接收**，无需订阅：

### 6.1 视频
```
16B 小端头: timestamp(long 8) + width(short 2) + height(short 2) + payloadLength(int 4)
+ JPEG payload
```
- 一条 WS binary message 一帧；
- 接收端必须按 `EndOfMessage` 聚合（WS 可能分片）；
- `payloadLength` 可用于校验。

### 6.2 音频
```
20B 小端头: startNanos(long 8) + endNanos(long 8) + sampleCount(int 4)
+ PCM（默认 441 samples × 2ch × 16bit = 1764B/块，10ms）
```
- 头部不含 sampleRate/channels；格式由配置固定/协商，默认 44100Hz/2ch/16bit；
- PCM 为小端 signed 16-bit interleaved；
- 慢消费者：服务端每连接发送队列上限 32，满则断开（日志 `慢连接发送队列满`）；测试客户端应快速排空或降低订阅时长。

---

## 七、游戏内命令 `/captureapi` v2

> 定位：玩家手动测试/演示，**不是正式对外 API**。已确认 dev 启动日志打印 `/captureapi v2 指令集已注册`；完整参数/所有子命令尚未系统性实测。
> 参数语法：空格分隔 `key:value`，如 `pos:0,90,0 rot:0,90 fov:70 filter:grayscale overlay:watermark@0.9,0.1,0.3,0.3,0,0.8`。
> `/captureapi` 单独输入没有默认执行；请用 `/captureapi help`。

### 7.1 capture
```
/captureapi capture [参数]
```
支持参数：
- `pos:x,y,z`
- `rot:yaw,pitch[,roll]`
- `fov:1..178.9`
- `filter:名称` / `filter:none`
- `overlay:名称@x,y,scaleX,scaleY,rotation,opacity` / `overlay:none`
- `quality:1..100`（请求级重编码，已生效）
- `res:宽x高`
- `audio:秒数`（最多 60；仅返回/提示采样数，不写音频文件）
- `out:文件路径`

示例：
```
/captureapi capture pos:0,90,0 rot:0,90 fov:70 res:1280x1024 out:E:\captures\top.png
/captureapi capture filter:grayscale overlay:watermark@0.9,0.1,0.2,0.2,0,0.8
/captureapi capture audio:1
```
无 `out` 时文件写入 `recording_output_dir`（默认 `config/captureapi/recordings`），文件名 `capture_<纳秒>.png/jpg`。

### 7.2 camera
```
/captureapi camera create [pos:..][rot:..][fov:..][res:..][bound:UUID|UUID@ox,oy,oz][filter:..][overlay:..]
/captureapi camera adjust <id> [pos:..][rot:..][fov:..][res:..][filter:..][overlay:..]
/captureapi camera list
/captureapi camera status
/captureapi camera activate <id>
/captureapi camera pause <id> / resume <id>
/captureapi camera delete <id>
```
**实际限制**：create 支持 `bound`；adjust 不支持 `bound`；**不支持 shape**（帮助文案可能提到 sphere，但代码未解析）；filter/overlay 为全局状态。

### 7.3 record
```
/captureapi record start [fps:1..240] [audio:true|false] [out:路径]
/captureapi record stop
/captureapi record status
```
- 只录 active 相机；
- `audio:true` 录制环回音频（临时 PCM + 停止 AAC 合流）；
- 无 `out` 时写 `recording_output_dir`。

### 7.4 管理/信息
```
/captureapi filter list|reload
/captureapi overlay list|reload
/captureapi renderfilter list|reload
/captureapi audiofilter list|reload
/captureapi info health
/captureapi info frame
/captureapi info shader
/captureapi info config [key]
/captureapi info audio <x> <y> <z>       # 只采 1 拍，返回统计
/captureapi set <key> <value>           # 全局默认参数
/captureapi help
```

---

## 八、Java API 完整用法

### 8.1 入口与方法

```java
import com.captureapi.net.CaptureApi;
import com.captureapi.net.FrameRequest;
import com.captureapi.net.FrameResult;
import com.captureapi.net.CaptureApi.CaptureOutcome;
```

| 方法 | 说明 | 线程 |
|---|---|---|
| `latestFrame()` | 最新 `JpegFrame` 或 null | 任意线程只读 |
| `latestCameraFrame(id)` | 指定相机最新帧：active/0 走总线，非 active 走多相机缓存 | 任意线程只读 |
| `grabFrame(CameraCreateParams,timeoutMs)` | 临时相机渲一帧 Future | 任意线程提交；渲染线程完成 |
| `captureFrame(FrameRequest)` | 同步取帧（FILE/HTTP），不取音频 | **禁止渲染线程** |
| `capture(FrameRequest)` | 帧 + 可选音频（推荐统一入口） | **禁止渲染线程**，可能阻塞到音频完成 |
| `latestAudioChunk()` | 最近音频块（非消费） | 任意线程 |
| `grabAudioSection(x,y,z,chunks)` | 虚拟位置音频截面 Future | 任意线程 |
| `createCamera(CameraCreateParams)` 等 7 个 | 相机 CRUD/激活/列表 | 任意线程 |
| `RecordingSession.get().start/stop/status` | 录像（不在门面） | stop 可能阻塞，勿渲染线程 |

### 8.2 取帧（文件）

```java
FrameRequest request = FrameRequest.builder()
        .position(100, 80, 200)
        .rotation(0, 45, 0)
        .fov(70)
        .resolution(1280, 1024)
        .format(FrameRequest.Format.PNG)
        .outputPath(Path.of("E:\\captures\\frame.png"))
        .temporary(true)
        .timeoutMillis(5000)
        .build();
FrameResult result = CaptureApi.captureFrame(request);
System.out.println(result.location() + " " + result.sizeBytes());
```

### 8.3 取帧 + 音频

```java
FrameRequest request = FrameRequest.builder()
        .position(100, 80, 200)
        .rotation(0, 90, 0)
        .temporary(true)
        .audio(true, 5)       // 5 拍
        .outputPath(Path.of("E:\\captures\\frame_with_audio.png"))
        .format(FrameRequest.Format.PNG)
        .build();
CaptureOutcome outcome = CaptureApi.capture(request); // 非渲染线程
AudioChunk audio = outcome.audio();                   // 可能为 null
```
> 音频采集位置优先 request 的位置/绑定实体；无显式位置用 active 相机；两者都无为 `(0,0,0)`。

### 8.4 取帧 + HTTP URL

```java
FrameResult result = CaptureApi.captureFrame(FrameRequest.builder()
        .position(100, 80, 200).rotation(0, 90, 0)
        .temporary(true)
        .outputMethod(FrameRequest.OutputMethod.HTTP)
        .build());
// result.location() = http://127.0.0.1:25580/camera/0/frame
```

### 8.5 滤镜/叠加覆盖

```java
FrameRequest request = FrameRequest.builder()
        .position(100, 80, 200).rotation(0, 90, 0).temporary(true)
        .filter("grayscale")
        .overlay(new OverlayModel("watermark", 0.9, 0.1, 0.2, 0.2, 0, 0.8))
        .outputPath(Path.of("E:\\captures\\filtered.png"))
        .build();
CaptureApi.captureFrame(request);
```
> 这是**全局状态**修改，capture 后仍保持；需要恢复请调用 `FilterRegistry.select("")`、`OverlayRegistry.clear()`。

### 8.6 相机

```java
CameraSpec cam = CaptureApi.createCamera(CameraCreateParams.builder()
        .position(100, 80, 200)
        .rotation(0, 30, 0)
        .fov(70)
        .build());
CaptureApi.activateCamera(cam.id());
List<CameraSpec> all = CaptureApi.listCameras();
```

### 8.7 已知 Java API 缺口

- `quality` 已生效（与全局不同时重编码，增加 CPU 成本）；
- `continuous` 未实现；
- `captureFrame` 不取音频；需 `capture`；
- `capture` 音频位置取 active，不取 request 坐标；
- 滤镜/叠加非隔离；
- 录像不在 `CaptureApi`，需直接 `RecordingSession`；
- 没有统一异常类型（IOException/IllegalArgumentException/UnsupportedOperationException/Future 异常混用）。

---

## 九、录像（视频/音频）

### 9.1 WS / Java
```json
{"id":"r1","action":"recording.start","params":{
  "cameraId":0,"fps":30,"jpegQuality":75,
  "includeAudio":true,
  "outputPath":"E:\\captures\\recording.mp4"
}}
```
停止/状态：
```json
{"id":"r2","action":"recording.stop"}
{"id":"r3","action":"recording.status"}
```
Java：
```java
RecordingSession.get().start(new RecordingParams(0, 30, 75, true, "E:\\captures\\recording.mp4"));
RecordingStatus st = RecordingSession.get().stop();
```
- `includeAudio=true`：停录后 FFmpeg 第二遍把临时 PCM 合流为 AAC；成功时 `audioAvailable=true`；
- 合流失败/无 PCM：回退纯视频，`audioAvailable=false`；
- `stop()` 包含线程 join + FFmpeg 合流，可能阻塞约 0.5s（实测 0.47s，短录）；
- 长录/音画漂移/HUD 内容未验证。

---

## 九点五、P2.3 v2 多相机独立渲染

- 存在多个相机时，`MultiCameraRenderer` 每 250ms 轮询一个**非 active** 相机，用该相机自己的
  `TextureTarget + PboPair + JpegEncoder` 独立渲染一帧，写入 `CameraFrameStore`；
- active 相机继续走原有连续 `VideoFrameBus`；
- HTTP `/camera/{id}/frame|stream` 与 Java `CaptureApi.latestCameraFrame(id)` 可按 id 读取；
- 已做每相机独立 FBO/PBO/编码器；但仍是**渲染线程时间片顺序**，不是同一帧并行多路；active 流会因额外渲染降帧；
- `camera.pause` 的相机不参与；删除相机后 GL 资源/缓存清理；
- `/health` 暴露 `multicam_cached_cameras/multicam_interval_ms`。

dev 实测（2026-11-02）：cam1 active 238,053B、cam2 非 active 203,814B，四次采样时间戳持续更新；无 GL/Mixin 错误；证据 `docs/evidence/S6/multicam-v2.json`。

## 十、参数（config）

### 10.1 WS / 游戏
```json
{"id":"g","action":"config.get","params":{"key":"fps_max"}}
{"id":"s","action":"config.set","params":{"key":"fps_max","value":15}}
```
游戏：
```
/captureapi info config fps_max
/captureapi set fps_max 15
```

### 10.2 即时生效键（示例）
`fps_max/jpeg_quality/encode_async/near_plane/far_plane/sound_range/chunk_rescan_ms/audio_enabled/poll_interval_ms/max_connections/max_qps/frame_cache_ttl_sec/sync_monitor_enabled/warn_threshold_ms/persist_changes/filter_directory/overlay_directory/overlay_enabled/render_blacklist_file/render_whitelist_file/audio_blacklist_file/audio_whitelist_file/max_cameras/recording_output_dir/ffmpeg_path/log_*`

### 10.3 需重启（运行时拒绝）
`video_resolution/sample_rate/bit_depth/channels/thread_priority/ring_capacity_sec/net_port/bind_local_only/token_enabled/recording_format`

> `persist_changes=true` 时尝试写回 TOML；服务端组件另有独立 `world/serverconfig/captureapi_server-server.toml`（修改必须无 BOM）。

---

## 十一、滤镜 / 叠加 / 名单

### 11.1 JSON 滤镜
目录：`config/captureapi/filters/*.json`
```json
{"name":"my","version":1,"ops":[
  {"type":"brightness","params":{"value":0.1}},
  {"type":"grayscale"}
]}
```
- ops：`brightness`(-1..1)/`contrast`(-1..1)/`saturation`(0..2)/`hue`(-180..180)/`grayscale`/`invert`；
- 选择：WS `filter.set` / Java `FilterRegistry.select` / 游戏参数 `filter:`;
- 空选择关闭；内置 `grayscale`。

### 11.2 叠加
目录：`config/captureapi/overlays/*.png`
WS `overlay.show` 参数：`image,x,y,scaleX,scaleY,rotation,opacity`；需 `overlay_enabled=true`；
游戏：`overlay:name@x,y,sx,sy,rot,opacity`；`overlay:none` 清空。
> 叠加/滤镜均为全局状态；`capture` 的参数不是隔离的一次性 overlay。

**GPU 叠加（P2.4 v1）**：JVM 属性 `-Dcaptureapi.overlay.gpu=true` 时启用；在离屏 FBO 回读前用
OpenGL 绘制图层，CPU 合成自动跳过。默认关闭仍走 CPU。限制：纹理按名称缓存、overlay.reload 后旧纹理
不立即删除；只支持矩形贴图；不进入主画面。dev 已实测红色矩形可见，证据
`docs/evidence/S6/gpu-overlay-v1.json`。

### 11.3 渲染/音频名单
- 文件：`config/captureapi/render_blacklist.txt`、`render_whitelist.txt`、`audio_blacklist.txt`、`audio_whitelist.txt`；
- `renderfilter`：离屏渲染窗口内按实体注册名过滤（EntityRenderDispatcher HEAD）；主画面不受影响；方块/方块实体/粒子暂未覆盖；
- `audiofilter`：SoundEngine.play HEAD 全局过滤；**会影响主画面音频**（当前没有 capture-only 混音分离）；
- 名单为空时零行为。

---

## 十二、M9 专用服组件

### 12.1 安装
- 服务端 mods：`captureapi-0.5.0-server.jar`（独立 modid `captureapi_server`）；
- 客户端：`captureapi-0.5.0.jar`；
- 详见 `docs/专用服部署指南.md`。

### 12.2 能力
- 协议 v1：握手、区块申请 `ChunkRequest(long[])`、释放 `ChunkRelease(all,long[])`、受理响应 `ServerResponse(ok,code,accepted)`；
- 服务端限制：`max_chunks_per_player`/`max_chunks_global`/`max_requests_per_second`、权限文件 UUID→bool；
- 客户端 `/health`：`remote_ready/requests/accepted/last_code`；
- 生产 Forge 专用服实测：握手、113/230 区块受理、断线回收、权限/限额/多玩家均 PASS（见 `docs/evidence/P2.5/`）。

### 12.3 当前客户端状态
- 单机：no-op；
- 远程 MP：自动握手，planner 差分申请/释放；服务端未装组件则关闭。

---

## 十三、能力矩阵（现状）

| 能力 | 状态 |
|---|---|
| 世界内单帧/流式/MJPEG | ✅ S1/S3 PASS |
| 相机 CRUD/调整/暂停 | ✅ |
| WS/HTTP/Java 取帧 | ✅ |
| 音频 PCM/时间戳 | ✅ S2.2/S2.3 PASS |
| 监听器方向性 | ✅ 左右/翻转；用户听辨待 |
| 音频截面 | ✅ 首版（语义待冻结） |
| 录像 + 音频 | ✅ 短录 PASS；长录待 |
| 运行时参数/JSON 滤镜/CPU 叠加 | ✅ |
| 渲染/音频名单实际过滤 | ⚠️ 首版挂接：离屏实体/全局音频；方块/粒子与 capture-only 未覆盖 |
| 远程 MP/M9 | ✅ dev+生产主链路 PASS |
| GLSL 滤镜 | ❌ P2.1 裁剪 |
| 多路独立渲染 | ⚠️ P2.3 v2：每相机独立 FBO/PBO/编码器，时间片顺序渲染（约250ms）；非同一帧并行 |
| GPU 叠加 | ❌ P2.4 |
| Oculus 离屏适配 | ❌ W-043；仅探测 |
| continuous 连续帧 | ❌ Unsupported |
| `quality` 参数 | ✅ 请求级重编码已实现（q10/q90 实测） |

---

## 十四、常见问题

| 现象 | 原因/处理 |
|---|---|
| `/captureapi` 无命令 | 需进入世界/确认 Forge 客户端命令注册；查看日志 `[CMD] /captureapi v2` |
| `capture` 返回旧画面 | active 存在且无 `pos/rot`、无 `temporary:true` → 用了最新缓存帧；加 `temporary:true` 或显式 pos/rot |
| 滤镜/叠加没效果 | 滤镜/叠加需先编码；确认滤镜名、overlay_enabled、贴图存在 |
| 音频截面失败 | 环回未激活/无数据；`audio status` / `/health audio_ring_written` |
| 录像无声 | `includeAudio=false`、FFmpeg 缺失、合流失败；看 `audioAvailable`/日志 |
| WS 慢消费者断开 | 发送队列 32 满；缩短测试窗口/快速排空/降低视频压力 |
| HTTP 帧 204 | 无 active 相机或暂无缓存帧 |
| HTTP 404 E_CAMERA_NOT_ACTIVE | id 非 active；用 id=0 或先 activate |
| token 开启后 401 | HTTP 加 `X-CaptureAPI-Token`，WS 加 `?token=` |
| Oculus 黑屏/花屏 | W-043 未适配；用 `status shader` 记录 backend |

---

## 十五、与计划端旧文档的差异（重要）

`docs/API全集与测试指令.md`（计划端初稿）与当前代码不一致的要点：

| 旧文档说法 | 当前实际 |
|---|---|
| 录像音频未实现 | **已实现**：includeAudio=true 临时 PCM + AAC 合流，`audioAvailable=true` |
| M9 客户端申请未实现 | **已实现**并生产专用服 PASS |
| `audio.section` 返回 PCM Base64 | WS `audio.section` 返回 **samples/bytes/rms/peak/start/end**；只有 `capture` 的 result.audio 含 pcmBase64 |
| WS 视频头 1B 类型 + … | 实际 **16B 小端头**：timestamp/width/height/payloadLength |
| WS 音频头含 sampleRate/channels | 实际 **20B**：start/end/sampleCount；格式由配置固定 |
| 测试指令参数 `worldName`/`x,y,z` | 实际 `client.loadWorld` 用 `name`；`client.playPosSound` 用 `dx,dy,dz,volume` |
| 错误码 E_CAMERA_LIMIT/E_NOT_IMPLEMENTED | 实际相机上限为 E_RATE_LIMIT；无 E_NOT_IMPLEMENTED |
| HTTP `/health` 字段 | 以本文 4.1 实际字段为准（旧文档示例字段不全/有误） |
| 录像 `jpegQuality` 生效 | 已实现请求级重编码；默认=全局 jpeg_quality |
| 游戏命令 `/get_image` | 已移除；现为 `/captureapi capture` |
| `/captureapi` v2 参数 | 以本文第七节为准（旧文档未覆盖 v2） |

---

## 十六、建议的最小验证命令

```bash
# 1) 服务健康
curl http://127.0.0.1:25580/health

# 2) WS capture（PowerShell/python 客户端）
# 见本文 5.2
# 3) 单帧 HTTP
curl -o frame.jpg http://127.0.0.1:25580/camera/0/frame

# 4) MJPEG 10s
ffmpeg -y -i http://127.0.0.1:25580/camera/0/stream -t 10 -c copy mjpeg.mkv

# 5) 游戏内（进入世界后）
/captureapi help
/captureapi capture pos:0,90,0 rot:0,90 fov:70 out:E:\captures\top.png
/captureapi capture audio:1
/captureapi record start fps:30 audio:true out:E:\captures\rec.mp4
/captureapi record stop
```

---

## 十七、本轮非实测收口（2026-11-02）

- `quality` 请求级重编码已实现并实测（q10 45,202B / q90 331,068B）；
- 滤镜/叠加在 captureFrame 内快照/恢复，不再永久污染全局；`CAPTURE_LOCK` 串行化；
- capture 音频位置改为 request 位置优先（显式 pos/绑定实体→active→0,0,0）；
- AudioRingBuffer.write/snapshotRecent/peekLatest 加锁（P1.2 快照一致性）；
- P1.7 配置持久化增加“保存前备份 + 保存后自校验 + 失败回滚文件”；
- P2.2 首版挂接：离屏实体渲染名单 + SoundEngine 音频名单；
- P1.4 录像 `jpegQuality` 已实现请求级重编码；
- P2.3 v2 多相机独立 FBO/PBO/编码器已实现（时间片循环；HTTP/Java 按相机 id 读取；非同一帧并行）；
- P2.4 GPU 叠加 v1 已实现（JVM 属性 `captureapi.overlay.gpu=true`，默认 CPU；证据 gpu-overlay-v1.json）；
- P2.1 GLSL 仍按计划裁剪条件不实施。

## 十八、版本与变更摘要

计划端本次新增/调整（`7da3b77..6782b9a`）：

1. `docs/API全集与测试指令.md`：全量手册初稿（部分状态已过时）；
2. `CaptureCommand`（`core`）：游戏内 `/captureapi` v1→v2，key:value 函数式；
3. `FrameRequest`：新增 `filter`/`overlays`/`overlay(OverlayModel)`；`quality` 现已请求级重编码；
4. `CaptureApi`：新增 `capture(FrameRequest)` + `CaptureOutcome`；filter/overlay 覆盖在 `captureFrame` 内；移除 `includeAudio` 直接 Unsupported；
5. `ControlCommandDispatcher`：新增 WS `capture`，映射到同一 `CaptureApi.capture()`；
6. 临时聊天壳 `/get_image` 已移除；当前游戏入口为 `/captureapi`（v2）；
7. 旧 `JavaAPI门面.md`、`录像功能.md` 等仍部分过时，需按本文/代码逐步同步。

> 本文是使用说明，不替代计划端接口裁决。剩余语义缺口：`continuous` 未实现、游戏命令仅注册验证、滤镜/叠加为全局串行锁（非并发隔离）、capture 质量重编码增加 CPU 成本，应在正式冻结前决定。
