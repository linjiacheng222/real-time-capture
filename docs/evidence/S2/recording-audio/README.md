# P1.4 录像音频轨首版证据（2026-11-01 22:07）

- 录制：相机 id=1，fps=10，1280×1024，includeAudio=true，输出 `recording-audio.mp4`；
- 播放 4 次铃铛测试声后停止；
- 状态：frames=102，audioChunks=190，audioAvailable=true；
- ffmpeg 解析（`ffmpeg-probe.txt`）：
  - 时长 1.90s，视频 h264 1280x1024 10fps；
  - **音频 aac (LC), 44100 Hz, stereo, 131 kb/s**；
- 实现：录制期间音频线程从环形缓冲快照写临时 PCM；停止时 FFmpeg 第二输入流合流 AAC，成功后删除临时文件；PCM 缺失/合流失败回退纯视频并标记 audioAvailable=false。
- 已知问题：
  - 测试时 `recording.stop` 响应超过脚本 5s 等待（实际后台完成，随后 status 显示 audioAvailable=true）；已把采集/音频线程 join 从 2s 收紧到 1s，**需下一次运行复测停止耗时**；
  - 音频为环形缓冲轮询快照，极端断档只告警，可能造成轻微音画偏移；长录/高负载待 S5 长跑验证；
  - 仅 mp4/有 FFmpeg 环境可用；无 FFmpeg 时仍回退纯视频。

## 停止耗时复测（2026-11-01 22:10）

- 新录 `recording-audio-retest.mp4`：1.06s，frames=44，audioAvailable=true；
- **recording.stop 响应 0.47s**（join 收紧后），状态立即变为 running=false；
- ffmpeg 解析：h264 1280x1024 10fps + **aac 44100Hz stereo 127kb/s**（`ffmpeg-probe-retest.txt`）；
- 结论：P1.4 停止 UX 问题已修复；文件含音频轨稳定 PASS。

