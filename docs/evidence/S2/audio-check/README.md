# S2 音频数据检查证据（2026-11-01 21:16）

> 自动部分：**S2.2 PASS（WS 音频块格式/时间戳）**、**S2.3 PASS（PCM 非静音/有起伏）**。
> S2.1 听觉中继与 S2.4 六方位听辨需用户戴耳机确认（当前证据只覆盖数据层）。

## 结果

| 项 | 结果 |
|---|---|
| WS 音频块 | 709 块；头 20B（start/end/sampleCount）+ PCM；`badFormat=0` |
| 每块格式 | 441 采样 / 1764B（10ms，44100Hz/2ch/16bit） |
| 时间戳 | start/end 单调（monotonic=True） |
| baseline PCM | 233 块 / 103194 立体声帧；RMS=153.11，peak=1148，窗口 RMS 48.62–327.98 |
| 触发铃铛后 | 474 块 / 209475 帧；RMS=182.13，peak=1264，窗口 RMS 34.30–491.52 |
| 结论 | 非静音、有起伏；触发后峰值/均值上升 |

## 文件

| 文件 | 说明 |
|---|---|
| `pcm-baseline.raw` / `.wav` | 基线 2s PCM（44.1kHz/16bit/立体声） |
| `pcm-sound.raw` / `.wav` | 触发 `client.playTestSound`（note_block_bell）后 4s PCM |
| `audio-baseline.csv` / `audio-sound.csv` | 每块 start/end/samples/payload |
| `audio-analysis.txt` | RMS/peak/窗口统计 |
| `health-before.json` / `health-after.json` | 健康快照 |
| `summary.txt` | 脚本日志 |

## 发现

- 自研 WS 服务端发送的消息可能**分片**（WebSocket fragmentation）；接收端必须按 `EndOfMessage` 聚合，否则会把片段误当 20B 音频头（首次运行出现 26 个坏块，修复接收端后 0）。这是对接客户端需要注意的协议细节，服务端行为本身符合 WebSocket 分片语义。

## 待用户确认（S2.1/S2.4）

1. 启动 PCL 实例（已装最新 captureapi jar），进入世界，戴耳机；
2. S2.1：确认游戏声音正常、无静音/断流/明显异常回声约 30s；
3. S2.4：六方位听辨需配合下一轮的“六方位测试音”指令/操作单（当前尚未提供）。
