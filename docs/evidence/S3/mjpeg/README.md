# S3 MJPEG 客户端兼容（FFmpeg，2026-11-01 21:51）

命令：

```
ffmpeg -y -loglevel warning -t 6 -i http://127.0.0.1:25580/camera/0/stream -frames:v 5 frame-%02d.jpg
```

结果：**exit=0，成功输出 5 张 JPEG**（frame-01..05.jpg，15–16KB）；ffmpeg 日志仅末尾有 `EOI missing, emulating` 警告（停止读取时流末尾截断，属预期）。浏览器/OpenCV 未安装/未执行。

状态：**MJPEG 实际客户端消费 PASS（FFmpeg）**；浏览器/OpenCV 兼容待后续。
