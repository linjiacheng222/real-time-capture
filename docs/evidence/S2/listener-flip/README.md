# S2.4 监听器翻转测试（2026-11-01 21:24）

> **结论：S2.4 未通过/发现设计缺口**。相机从 yaw=0 转到 yaw=180 后，固定 +X（世界东侧）声源的左右声道优势没有翻转，说明环回混音没有跟随相机 Listener，仍接近玩家 Listener 或未同步生效。

## 测试设计

- 相机位于玩家眼位，yaw=0 / yaw=180，pitch=0；
- 在相机 +X 方向 8 格播放 3D 音符盒（volume=4）；
- 每个 segment 用**独立 WS 音频连接**抓 3s PCM；
- 分析左右声道 RMS。

## 数据

| Segment | L RMS | R RMS | L-R |
|---|---|---|---|
| yaw0-silence | 126.80 | 179.49 | -52.69 |
| yaw0-sound | **479.63** | 106.65 | +372.98 |
| yaw180-silence | 431.39 | 549.03 | -117.64 |
| yaw180-sound | **482.32** | 296.58 | +185.74 |

**期望**：yaw0 时 +X 是相机左侧（L 高）；yaw180 时 +X 应变为相机右侧（R 高）。
**实际**：yaw180 时仍 L 高 → 不翻转。

## 分析

- 当前实现 `AudioListenerSync` 在 `CameraSyncHook` 的 before/after 中写入/恢复 OpenAL Listener，但 before/after 包住的是**渲染线程的 renderLevel**；音频采集线程的 `alcRenderSamplesSOFT` 在此窗口之外执行，绝大多数时间看到的是玩家 Listener。
- 因此“监听器同步”对捕获音频方向性**没有实际效果**；这也解释 S2.4 早前数据不稳定。
- 这是架构/时序问题，非简单参数问题；需要改为**采集线程在每次 loopback render 前应用相机 Listener**，或双混音路径（玩家播放 vs 捕获），并重新评估“玩家听觉零扰动”与“相机方向性”的取舍。

## 文件

`pcm-yaw0-silence.raw` / `pcm-yaw0-sound.raw` / `pcm-yaw180-silence.raw` / `pcm-yaw180-sound.raw`，脚本 `scripts/s2-listener-flip-check.ps1`。
