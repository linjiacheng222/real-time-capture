# S3 E2E 自动检查证据（2026-11-01 21:25）

> 自动部分：**E2E-1/2/3/5/6 通过**；E2E-4（Python 对齐客户端）未运行（环境无 websocket-client），E2E-7 预检此前 PASS；MJPEG 真实播放器（浏览器/OpenCV/FFmpeg）未执行。

| 用例 | 结果 | 证据 |
|---|---|---|
| E2E-1 单帧 HTTP | **PASS**：200，JPEG（27948B），X-Frame-Timestamp 存在 | `frame-e2e1.jpg`、`frame-headers.json` |
| E2E-2 WS 音视频 | **PASS**：3s 内 video=79、audio=301、bad=0 | summary |
| E2E-3 MJPEG | **PASS（仅头）**：200，multipart/x-mixed-replace，boundary=captureapi-frame-boundary | summary |
| E2E-5 控制/错误码 | **PASS**：camera.adjust 正常；unknown.action → `E_UNKNOWN_ACTION` | summary |
| E2E-6 模式切换/泄漏 | **PASS**：frame→WS→frame 均 200；http_streams=0、connections=0 | summary |
| E2E-4 Python 对齐 | 未运行 | websocket-client 缺失；有 PowerShell 抓流脚本替代 |
| E2E-7 回归预检 | 之前 PASS（5/5） | docs/evidence/M7/预检 |

**缺**：MJPEG 三客户端真实播放、Python 对齐脚本、浏览器长时间断流测试。
