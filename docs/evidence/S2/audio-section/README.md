# P1.2 音频截面首版证据（2026-11-01 22:00）

- 指令：`audio.section`（调试辅助，内部调用 CaptureApi.grabAudioSection）；
- 实测：
  - chunks=5 → samples=2205 / bytes=8820 / rms=36.19 / peak=128（见 section5.json）
  - chunks=10 → samples=4410 / bytes=17640 / rms=41.73 / peak=126（见 section10.json）
- 实现：虚拟 listener 窗口 + 环形缓冲最近 N 拍快照；不推进消费游标；
- 限制：快照物理槽并发覆盖窗口、真实虚拟位置听感/与流式消费协调待测试；WS 指令为测试辅助，发布前移除/门控。
