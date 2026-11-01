# S5 稳定性短测 + 长跑操作单（2026-11-01 21:50）

## 短测结果（120s，相机激活 + 周期调整）

| 指标 | 结果 |
|---|---|
| 时长 | 120s |
| 帧数增量 | **3759**（约 31.3fps） |
| pipeline_failed | 0 → 0 |
| encode_failed | 0 → 0 |
| encode_dropped | 0 |
| heap 起/止/峰 | 469.8 / 440.6 / 494.2 MB（无持续增长） |
| 结论 | **PASS（2 分钟烟测；≠4h 长跑）** |

证据：`docs/evidence/S5/stability-120s/`（samples.csv、summary.txt、health-after.json）。

## ≥4h 长跑最小操作单（需用户）

1. 启动 PCL 实例（已装最新 jar），进入世界；
2. 仓库目录运行：
   ```
   powershell -ExecutionPolicy Bypass -File scripts\s5-long-run-start.ps1
   ```
   脚本会在玩家眼位创建相机并保持；输出相机 id；
3. **另开一个终端**运行：
   ```
   powershell -ExecutionPolicy Bypass -File scripts\long-run-health-log.ps1 -DurationSeconds 14400
   ```
   正常游戏/挂机 ≥4 小时；脚本每 5 秒写健康 CSV；
4. 结束后把 `docs/evidence/S5/long-run/health-*.csv` 与体感（卡顿/崩溃/断流）反馈给开发端；
5. 若需收尾，可重跑长跑开始脚本或重启客户端清空相机。

**判据**：无崩溃；帧数持续增长；pipeline_failed/encode_failed/encode_dropped 无持续增长；heap 走势平稳（无单调上升）。
