# S4 性能/资源自动检查（2026-11-01 21:34–21:36）

> 结论：**S4 未通过**。单帧延迟优秀，但帧率低于目标且编码段超预算：
> - 帧率 **26.3–26.9 fps**（目标 30±2）；
> - 流水线 total **≈31.1–31.5ms**（预算 <23ms），其中 **JPEG 编码≈28.4ms** 占绝对主导；
> - 单帧 HTTP p95 **1.1–1.4ms**（PASS）。

## 数据（相机激活，1280×1024，fps_max=30，Embddium+ModernFix）

| 指标 | 实测 | 判据 | 结论 |
|---|---|---|---|
| 发布帧率 | 26.94 / 26.28 / 26.72 fps（25s/15s/10s） | 30±2 | **FAIL（低约 3–4fps）** |
| 单帧 HTTP P50/P95/max | 0.60–0.67 / 1.11–1.40 / 7.9–9.1 ms | P95<100ms | **PASS** |
| pipeline render | 0.29–0.31 ms | — | 正常 |
| pipeline readback | 2.4–2.6 ms | — | 正常 |
| pipeline encode | **28.4–28.6 ms** | 总<23ms | **FAIL（主因）** |
| pipeline total | **≈31.0–31.5 ms** | <23ms | **FAIL** |
| 资源/连接 | http_streams=0，connections=0，pipeline_failed=0 | 无泄漏 | 正常 |
| JVM heap 采样 | 脚本 `jcmd` 解析为空（待修） | 无持续增长 | 未取得 |

## 分析

- 编码是 1280×1024 RGBA→JPEG 的瓶颈；默认 `encode_async=false`，编码在渲染线程内联，单帧~28ms，直接压低帧率。
- 读回 2.4ms、渲染 0.3ms 均远低于预算；优化重点应是 `JpegEncoder`（复用 ImageWriter/BufferedImage/缓冲区，减少每帧对象分配与拷贝），或先验证 `encode_async=true` 的吞吐/资源曲线。
- 测试时游戏窗口未前台/可能被系统降帧，但 pipeline 数据与帧率结论一致，编码预算 FAIL 不受窗口状态影响。

## 文件

`samples.csv`、`health-before/after.json`、`summary.txt`、`latest.log`（含 `流水线耗时均值` 行）；脚本 `scripts/s4-perf-check.ps1`。

## 下一步

1. 优化 `JpegEncoder`（目标编码 <10ms/帧，总<15ms），并复测 30fps/预算；
2. 或评估 `encode_async=true` 下的帧率/编码队列/CPU 占用；
3. 修复 heap 采样（`jcmd GC.heap_info` 输出格式/权限）并补 30min 内存趋势；
4. S4 通过后再进入 S5 长跑/S6 红线终验。

## 追加：编码优化与异步/聚焦复测（2026-11-01 21:39–21:40）

- **JpegEncoder 优化**：复用 BufferedImage/行缓冲/BAOS，禁用 ImageIO 磁盘缓存，改用 MemoryCacheImageOutputStream；inline 编码 **28.4→26.45ms**（改善约 7%），total 31.5→28.2ms，仍超 23ms；fps 26.1。
- **encode_async=true（运行时）**：聚焦窗口后总流水线 **≈10.3ms（PASS）**，其中 encode 指标 8.7ms；但 `video_frames_published` 仍 **27.55fps**。
- 判断：async 已把流水线预算压到 <23ms；27.55fps 可能受**主游戏帧率/环境**限制，需用新增 health 计数（game_fps/offscreen_rendered/readback/encoded）区分“渲染帧率”与“编码发布帧率”。
- 数据目录：`perf-optimized/`、`perf-async/`、`perf-focused-async/`。
- 下一步：启动后采样新 health 计数，判断瓶颈是主循环 fps、编码发布还是限流；决定默认 inline/async 及进一步优化。

