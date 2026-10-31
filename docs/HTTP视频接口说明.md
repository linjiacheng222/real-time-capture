# HTTP 视频接口说明（T4.4 MJPEG / T4.5 单帧）

> 状态：**代码完成**（2026-11-01）；dev 运行时已验证单帧 204/404 语义、MJPEG 200 multipart 头、断开后 `http_streams` 归零、WS 慢消费者被断（slow_disconnects=1）；实际出图/三类客户端兼容待测试分计划。
> 关联：I4.4.1~3、I4.5.1~2、T4.2 同源帧缓存、I4.1 鉴权/连接上限。

## 一、路由

| 方法/路径 | 语义 | 成功响应 |
|---|---|---|
| `GET /camera/{id}/frame` | 单帧轮询（只读缓存，不触发渲染） | `200 image/jpeg` + `X-Frame-Timestamp/Width/Height` + JPEG；无帧 `204` |
| `GET /camera/{id}/stream` | MJPEG 流（multipart/x-mixed-replace） | `200` + `Content-Type: multipart/x-mixed-replace; boundary=captureapi-frame-boundary` |

- `id=0` 表示当前 active 相机；非 active 的已存在相机返回 404 `E_CAMERA_NOT_ACTIVE`；无 active 时 frame 返回 204、stream 返回 404 `E_NO_ACTIVE_CAMERA`；格式非法 404。
- token 开启时与全局规则一致（`X-CaptureAPI-Token`/query），`/health` 免鉴权。

## 二、序列化

- 单帧：响应体为**裸 JPEG**（帧头不进响应体，元数据进 HTTP 头），供 OpenCV/ffprobe 等标准工具直读；
- MJPEG：每帧 `--boundary` + `Content-Type: image/jpeg` + `Content-Length` + `X-Frame-Timestamp` + JPEG；无新帧时每 1s 写空行心跳，用于及时发现客户端断开；
- 数据源与 WS 推流共用 `VideoFrameBus` 最新帧（同源，只读不触发渲染）。

## 三、并发/断开处理

- HttpServer 使用缓存线程池，每 MJPEG 长连接独立线程；
- `tryAcquireHttpStream()` 与 WS 连接共享 `max_connections` 上限，超限 503；断开/写异常在 finally 释放名额；
- 慢写 >5s 计数并断开；客户端断开后 `http_streams` 自动归零（心跳/下帧写失败触发）。

## 四、dev 运行时证据（2026-11-01）

```
/camera/0/frame（无 active） -> 204
/camera/999/frame -> 404
/camera/foo/frame -> 404
创建 active 相机后：/camera/0/frame -> 204（主菜单无世界帧，符合“无帧不挂起”）
/camera/0/stream -> HTTP 200，Content-Type: multipart/x-mixed-replace; boundary=captureapi-frame-boundary
断开 4s 后 health: http_streams=0
测试中 WS 客户端不读音频 -> slow_disconnects=1（发送队列满断开，符合 I4.2.5）
```

## 五、已知缺口

- 世界内实际出图（需进入世界触发离屏渲染）、浏览器 `<img>`/OpenCV/FFmpeg 三类客户端、帧率与 10 分钟稳定性未测；
- 单帧 `max_qps` 限速、并发轮询 P95 延迟、轮询/推流同帧时间戳一致性未测；
- 慢写超时依赖写入阻塞返回，实际 OS 缓冲下阈值行为待压测；HTTP 与 WS 共享连接上限目前为近似（httpStreams+WS 连接数）。
