# CaptureAPI 全量接口集与测试指令手册

> 日期：2026-11-02。本文涵盖本项目**全部可操作接口**（P0/P1/P2），含每条指令的 JSON 格式、参数、响应、可直接复制的测试命令（curl / Python / PowerShell），以及当前可用状态。

---

## 一、连接信息

| 项 | 值 |
|---|---|
| HTTP 地址 | `http://127.0.0.1:25580`（`net_port` 可配） |
| WebSocket 地址 | `ws://127.0.0.1:25581/`（`net_port+1` 自动推导） |
| 鉴权 | token 可选（默认关）；HTTP 头 `X-CaptureAPI-Token` / WS query `?token=`；`/health` 始终免鉴权 |
| 绑定 | 默认仅 127.0.0.1；`lan_enabled=true` + `bind_local_only=false` 时开放局域网 |

**快速检查服务是否就绪**：
```bash
curl http://127.0.0.1:25580/health
```

---

## 二、WS 控制指令（JSON 文本消息）

### 连接后发送格式

```json
{"id":"<配对id>","action":"<指令名>","params":{...}}
```

响应：
```json
{"id":"<配对id>","ok":true,"result":{...}}
{"id":"<配对id>","ok":false,"error":{"code":"E_XXX","message":"..."}}
```

---

### 2.1 相机管理（P0 ✅ 已实现）

#### 创建相机
```json
{"id":"c1","action":"camera.create","params":{
  "x":100,"y":64,"z":200,
  "yaw":45,"pitch":-10,"roll":0,
  "fov":70,
  "width":1280,"height":1024,
  "shape":"rect"
}}
```
- `x/y/z`：世界坐标（double，默认 0）
- `yaw/pitch/roll`：度（pitch ∈ [-90, 90]）
- `fov`：视场角（默认 70，范围 (1, 179)）
- `width/height`：分辨率（省略＝全局 `video_resolution`）
- `shape`：`rect`（默认）或 `sphere_cone`（需配 `axisYaw/axisPitch/openAngle/slant`）
- `boundEntity`：绑定实体 UUID（与 x/y/z 互斥）
- `offsetX/Y/Z`：绑定偏移

**返回**：完整 CameraSpec JSON（含 `id`）。首个相机自动成为 active。

#### 调整相机
```json
{"id":"c2","action":"camera.adjust","params":{"id":1,"x":150,"yaw":90}}
```
- `id`（必填）＋同 create 的全量参数（整体替换）

#### 激活（切换 active）
```json
{"id":"c3","action":"camera.activate","params":{"id":2}}
```

#### 暂停 / 恢复
```json
{"id":"c4","action":"camera.pause","params":{"id":1}}
{"id":"c5","action":"camera.resume","params":{"id":1}}
```

#### 删除
```json
{"id":"c6","action":"camera.delete","params":{"id":1}}
```

#### 列表 / 状态（信息 API）
```json
{"id":"l1","action":"camera.list"}
{"id":"s1","action":"camera.status"}
```
- `camera.list` → `{"active":1,"cameras":[{...},{...}]}`
- `camera.status` → `{"active":1,"count":1,"camera":{...}}`

---

### 2.2 参数读写（P0 ✅ 已实现）

#### 读取参数
```json
{"id":"g1","action":"config.get","params":{"key":"fps_max"}}
```

#### 写入参数
```json
{"id":"s1","action":"config.set","params":{"key":"fps_max","value":15}}
```

**即时生效键**（运行时可改）：
`fps_max` / `jpeg_quality` / `encode_async` / `near_plane` / `far_plane` / `sound_range` / `chunk_rescan_ms` / `audio_enabled` / `poll_interval_ms` / `max_connections` / `max_qps` / `frame_cache_ttl_sec` / `sync_monitor_enabled` / `warn_threshold_ms` / `persist_changes` / `recording_output_dir` / `ffmpeg_path` / `log_resource_lifecycle` / `log_frame_events` / `log_frame_drops` / `log_pipeline_timing` / `log_buffer_watermark` / `log_gl_thread_violation` / `clock_logging` / `filter_directory` / `overlay_directory` / `overlay_enabled` / `render_blacklist_file` / `render_whitelist_file` / `audio_blacklist_file` / `audio_whitelist_file` / `max_cameras` / `recording_format`

**需重启键**（运行时拒绝，提示重启）：
`video_resolution` / `sample_rate` / `bit_depth` / `channels` / `thread_priority` / `ring_capacity_sec` / `net_port` / `bind_local_only` / `token_enabled` / `lan_enabled`

---

### 2.3 滤镜控制（P1 ✅ 已实现）

```json
{"id":"f1","action":"filter.list"}
{"id":"f2","action":"filter.set","params":{"name":"grayscale"}}
{"id":"f3","action":"filter.set","params":{"name":""}}
{"id":"f4","action":"filter.reload"}
```
- `filter.set` 空名＝关闭滤镜
- 滤镜文件放 `config/captureapi/filters/*.json`
- 内置：`grayscale`（Rec.709 灰度）
- 自定义格式：`{"name":"my","version":1,"ops":[{"type":"brightness","params":{"value":0.1}}]}`
- 可用 op：`brightness` [-1,1] / `contrast` [-1,1] / `saturation` [0,2] / `hue` [-180,180] / `grayscale` / `invert`

---

### 2.4 贴图叠加控制（P2 ✅ 已实现）

```json
{"id":"o1","action":"overlay.list"}
{"id":"o2","action":"overlay.show","params":{
  "image":"watermark","x":0.95,"y":0.05,
  "scaleX":0.2,"scaleY":0.1,
  "rotation":0,"opacity":0.8
}}
{"id":"o3","action":"overlay.clear"}
{"id":"o4","action":"overlay.reload"}
```
- 贴图 PNG 放 `config/captureapi/overlays/*.png`（文件名＝叠加名）
- `x/y`：归一化中心位置（0~1）
- 需先 `config.set overlay_enabled true` 开启

---

### 2.5 渲染/音频黑白名单（P2 结构 ✅ / 实际过滤 ❌ 未挂接）

```json
{"id":"r1","action":"renderfilter.list"}
{"id":"r2","action":"renderfilter.reload"}
{"id":"a1","action":"audiofilter.list"}
{"id":"a2","action":"audiofilter.reload"}
```
- 名单文件：`config/captureapi/render_blacklist.txt` 等（每行一个注册名）
- **注意**：当前查询/重载可用，但**未接入离屏场景收集阶段**（画面不变）

---

### 2.6 录像控制（P1 ✅ 已实现 / 音频轨 ❌ 未实现）

```json
{"id":"v1","action":"recording.start","params":{
  "cameraId":0,
  "fps":30,
  "includeAudio":false
}}
{"id":"v2","action":"recording.stop"}
{"id":"v3","action":"recording.status"}
```
- `cameraId`：0＝当前 active
- `includeAudio`：当前**不支持**（明确提示，纯视频回退）
- 需系统安装 FFmpeg（`ffmpeg_path` 可配）
- 输出：`config/captureapi/recordings/recording_<纳秒>.mp4`

---

### 2.7 音频截面（P1 ✅ 已实现）

```json
{"id":"au1","action":"audio.section","params":{
  "x":100,"y":64,"z":200
}}
```
- 在指定虚拟位置采一拍 PCM
- 返回：`{pcm: Base64, timestampNanos, sampleRate, channels, samples}`

---

### 2.8 光影状态查询（P1 信息 API ✅）

```json
{"id":"sh1","action":"shader.status"}
```
- 返回：`{backend, detected, active}`（Oculus 探测结果）

---

### 2.9 测试专用指令（默认关闭）

需先运行 `scripts/enable-test-commands.ps1 -GameDir <实例目录>` 开启。

```json
{"id":"t1","action":"client.unpause"}
{"id":"t2","action":"client.playerPose"}
{"id":"t3","action":"client.screenshot"}
{"id":"t4","action":"client.leaveWorld"}
{"id":"t5","action":"client.loadWorld","params":{"worldName":"新建世界"}}
{"id":"t6","action":"client.playTestSound"}
{"id":"t7","action":"client.playPosSound","params":{"x":100,"y":64,"z":200}}
```

---

## 三、HTTP 端点

### 3.1 健康检查（免鉴权）

```
GET http://127.0.0.1:25580/health
```

```json
{
  "ok": true, "running": true,
  "http": true, "ws": true, "ws_port": 25581,
  "bind": "127.0.0.1", "lan_enabled": false,
  "connections": 0, "max_connections": 8,
  "rejected_connections": 0, "errors": 0,
  "uptime_ms": 12345, "token_required": false,
  "audio_loopback": true, "audio_ring_written": 1234,
  "audio_ring_dropped": 0, "audio_ring_silent": 0,
  "video_rendered_frames": 567, "video_failed_frames": 0,
  "video_readback_frames": 566,
  "shader_backend": "none", "shader_detected": false, "shader_active": false,
  "recording_running": false
}
```

**测试**：
```bash
curl http://127.0.0.1:25580/health | python -m json.tool
```

### 3.2 单帧获取

```
GET http://127.0.0.1:25580/camera/{id}/frame
```
- `id=0`：当前 active 相机
- 成功：`200 image/jpeg`＋头 `X-Frame-Timestamp` / `X-Frame-Width` / `X-Frame-Height`
- 无帧：`204`（不挂起）
- 无 active：`204`
- 非法 id：`404`

**测试**：
```bash
curl -o frame.jpg -D - http://127.0.0.1:25580/camera/0/frame
```

### 3.3 MJPEG 流

```
GET http://127.0.0.1:25580/camera/{id}/stream
```
- `Content-Type: multipart/x-mixed-replace; boundary=captureapi-frame-boundary`
- 浏览器直接打开可看视频流
- 无帧时每 1s 写心跳空行

**测试（浏览器）**：直接访问 `http://127.0.0.1:25580/camera/0/stream`

**测试（FFmpeg 录 10s）**：
```bash
ffmpeg -i http://127.0.0.1:25580/camera/0/stream -t 10 -c copy mjpeg_test.mp4
```

---

## 四、WS 二进制流协议

连接 WS 后自动接收两类二进制消息（不需发送订阅请求）。

### 4.1 视频帧

```
[1B 类型=0x01][8B 时间戳 nanos][2B 宽][2B 高][JPEG 数据]
```

### 4.2 音频块

```
[1B 类型=0x02][8B 起始时间戳][8B 结束时间戳][4B 采样率][2B 声道][4B 采样数][PCM 数据]
```
- 默认每 10ms 一块，441 采样/声道，1764 字节 PCM

---

## 五、Java API（模组内编程接口）

供其他 Java 模组通过依赖调用。入口类：`com.captureapi.net.CaptureApi`。

| 方法 | 说明 | 状态 |
|---|---|---|
| `CaptureApi.latestFrame()` | 最新 JPEG 帧 | ✅ |
| `CaptureApi.grabFrame(params, timeoutMs)` | 实时调取一帧（Future） | ✅ |
| `CaptureApi.captureFrame(FrameRequest)` | 统一帧获取（文件/HTTP 输出） | ✅ |
| `CaptureApi.createCamera(params)` | 创建相机 | ✅ |
| `CaptureApi.adjustCamera(id, params)` | 调整相机 | ✅ |
| `CaptureApi.deleteCamera(id)` | 删除相机 | ✅ |
| `CaptureApi.listCameras()` | 相机列表 | ✅ |
| `CaptureApi.latestAudioChunk()` | 最新音频块 | ✅ |
| `CaptureApi.grabAudioSection(x, y, z)` | 音频截面 | ✅ |

---

## 六、M9 服务端组件 API

服务端组件（`captureapi-0.5.0-server.jar`）装于专用服 `mods/`，通过 Forge `SimpleChannel` 通信。

| 消息 | 方向 | 说明 |
|---|---|---|
| 握手 | 客户端→服务端 | 协议版本 1 |
| 区块申请 | 客户端→服务端 | ChunkPos long[] |
| 区块释放 | 客户端→服务端 | 全部或指定区块 |
| 受理响应 | 服务端→客户端 | ok/code/accepted |

**注意**：客户端申请侧（T2.7.3）**未实现**，远程 MP 区块加载暂不可用。

---

## 七、一键测试脚本

### 7.1 Python WS 测试（通用）

```python
"""captureapi_test.py — 通用 WS 测试客户端"""
import json, websocket, sys, time

WS_URL = "ws://127.0.0.1:25581/"

def send(ws, action, params=None, req_id=None):
    msg = {"id": req_id or action, "action": action}
    if params: msg["params"] = params
    ws.send(json.dumps(msg))
    return json.loads(ws.recv())

def main():
    ws = websocket.create_connection(WS_URL, timeout=10)
    print("=== 健康检查 ===")
    print(send(ws, "camera.status"))
    print("\n=== 创建相机 ===")
    r = send(ws, "camera.create", {"x": 100, "y": 64, "z": 200, "fov": 70})
    print(r)
    cam_id = r.get("result", {}).get("id", 0)
    print(f"\n相机 id={cam_id}")
    print("\n=== 等待 3 秒取帧 ===")
    time.sleep(3)
    print("\n=== 相机列表 ===")
    print(send(ws, "camera.list"))
    print("\n=== 读取参数 ===")
    print(send(ws, "config.get", {"key": "fps_max"}))
    print("\n=== 滤镜列表 ===")
    print(send(ws, "filter.list"))
    print("\n=== 删除相机 ===")
    print(send(ws, "camera.delete", {"id": cam_id}))
    ws.close()
    print("\n测试完成")

if __name__ == "__main__":
    main()
```

**运行**：
```bash
pip install websocket-client
python captureapi_test.py
```

### 7.2 PowerShell 快速检查

```powershell
# 健康检查
Invoke-RestMethod http://127.0.0.1:25580/health

# 取单帧
Invoke-WebRequest http://127.0.0.1:25580/camera/0/frame -OutFile frame.jpg

# 查看健康详情
(Invoke-RestMethod http://127.0.0.1:25580/health) | ConvertTo-Json -Depth 3
```

### 7.3 完整功能遍历脚本

```python
"""captureapi_full_test.py — 全功能遍历"""
import json, websocket, time, base64, struct

WS = "ws://127.0.0.1:25581/"

def send(ws, action, params=None):
    msg = {"id": action, "action": action}
    if params: msg["params"] = params
    ws.send(json.dumps(msg))
    return json.loads(ws.recv())

def run():
    ws = websocket.create_connection(WS, timeout=15)
    results = []

    def test(name, action, params=None):
        r = send(ws, action, params)
        status = "✅" if r.get("ok") else "❌"
        results.append((name, status, r))
        print(f"  {status} {name}: {json.dumps(r, ensure_ascii=False)[:120]}")
        return r

    print("=== 1. 相机管理 ===")
    test("创建", "camera.create", {"x": 100, "y": 64, "z": 200, "fov": 70})
    test("列表", "camera.list")
    test("状态", "camera.status")
    test("调整", "camera.adjust", {"id": 1, "yaw": 90})
    test("暂停", "camera.pause", {"id": 1})
    test("恢复", "camera.resume", {"id": 1})

    print("\n=== 2. 参数 ===")
    test("读 fps_max", "config.get", {"key": "fps_max"})
    test("写 fps_max=15", "config.set", {"key": "fps_max", "value": 15})
    test("读回", "config.get", {"key": "fps_max"})
    test("恢复 30", "config.set", {"key": "fps_max", "value": 30})

    print("\n=== 3. 滤镜 ===")
    test("列表", "filter.list")
    test("设灰度", "filter.set", {"name": "grayscale"})
    test("关闭", "filter.set", {"name": ""})

    print("\n=== 4. 叠加 ===")
    test("列表", "overlay.list")

    print("\n=== 5. 名单 ===")
    test("渲染名单", "renderfilter.list")
    test("音频名单", "audiofilter.list")

    print("\n=== 6. 光影 ===")
    test("光影状态", "shader.status")

    print("\n=== 7. 录像 ===")
    test("录像状态", "recording.status")

    print("\n=== 8. 清理 ===")
    test("删除相机", "camera.delete", {"id": 1})

    ws.close()
    ok = sum(1 for _, s, _ in results if "✅" in s)
    print(f"\n=== 汇总：{ok}/{len(results)} 通过 ===")

if __name__ == "__main__":
    run()
```

### 7.4 音频数据检查

```python
"""captureapi_audio_test.py — 接收音频块并检查"""
import websocket, struct, time, sys

WS = "ws://127.0.0.1:25581/"

ws = websocket.create_connection(WS, timeout=10)
# 创建相机激活音频流
import json
ws.send(json.dumps({"id":"a","action":"camera.create","params":{"x":0,"y":64,"z":0}}))
print(ws.recv())

print("等待音频块（需进入世界）...")
audio_count = 0
video_count = 0
start = time.time()
try:
    while time.time() - start < 5:
        data = ws.recv()
        if isinstance(data, bytes) and len(data) > 0:
            msg_type = data[0]
            if msg_type == 0x01:
                video_count += 1
            elif msg_type == 0x02:
                audio_count += 1
                if audio_count == 1:
                    ts_start, ts_end = struct.unpack("<qq", data[1:17])
                    sample_rate, channels, samples = struct.unpack("<IhI", data[17:27])
                    pcm_bytes = len(data) - 27
                    print(f"首个音频块：ts={ts_start}, {sample_rate}Hz, {channels}ch, {samples}采样, {pcm_bytes}B PCM")
except websocket.WebSocketTimeoutException:
    pass
print(f"5 秒内：视频帧={video_count}, 音频块={audio_count}")
ws.close()
```

---

## 八、当前可用状态速查

| 功能 | 状态 | 前提 |
|---|---|---|
| 相机 CRUD | ✅ 可用 | — |
| 单帧 HTTP | ✅ 可用 | 进入世界 |
| MJPEG 流 | ✅ 可用 | 进入世界 |
| WS 推流（音视频） | ✅ 可用 | 进入世界 |
| WS 控制指令 | ✅ 可用 | — |
| 参数读写 | ✅ 可用 | — |
| 滤镜（JSON 参数式） | ✅ 可用 | 进入世界（画面效果） |
| 贴图叠加 | ✅ 可用 | 放入 PNG + 开 overlay_enabled |
| 录像（纯视频） | ✅ 可用 | 安装 FFmpeg + 进入世界 |
| 音频截面 | ✅ 可用 | 进入世界 |
| 渲染/音频名单 | ⚠️ 查询可用，画面过滤未挂接 | — |
| GLSL 滤镜 | ❌ 未实现 | — |
| 录像音频轨 | ❌ 未实现 | — |
| 多路独立渲染 | ❌ 未实现（多相机对象共存但单路渲染） | — |
| 远程 MP 区块加载 | ❌ 客户端申请侧未实现 | — |
| Oculus 光影 | ⚠️ 探测可用，离屏适配未实现 | — |

---

## 九、错误码表

| code | 含义 |
|---|---|
| `E_UNKNOWN_ACTION` | 指令不在白名单 |
| `E_MISSING_PARAM` | 必填参数缺失 |
| `E_PARAM_RANGE` | 参数类型/范围/格式非法 |
| `E_NOT_FOUND` | 对象（相机 id 等）不存在 |
| `E_INTERNAL` | 内部异常 |
| `E_CAMERA_LIMIT` | 超过 max_cameras |
| `E_RATE_LIMIT` | 频率/连接限制 |
| `E_NO_PERMISSION` | 权限拒绝（M9 专用服） |
| `E_NOT_IMPLEMENTED` | 功能未实现 |
