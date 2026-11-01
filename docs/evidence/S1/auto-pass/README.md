# S1 自动化通过证据（2026-11-01 21:00）

> `scripts/s1-capture-check.ps1` exit=0，目标环境（dev + Embeddium 0.3.31 + ModernFix 5.27.89）。

| 文件 | 说明 |
|---|---|
| `frame-original.jpg` | 相机 (-32,120,61)，yaw=0，pitch=60（俯视森林）；JPEG 1280×1024，mean≈38.66，std≈22.20 |
| `frame-moved.jpg` | 相机 (-20,120,61)，yaw=90，pitch=60；调整后 hash 改变（S1.2 通过） |
| `headers-*.json` | X-Frame-Timestamp / 宽高 |
| `health.json` | `/health` 快照 |
| `ws-create.json` | 相机创建响应 |
| `summary.txt` | 自动脚本完整日志与结论 |

**判据**：S1.1 帧存在且 JPEG 可解、非全黑（mean 38.66/std 22.20）；S1.2 调整相机后帧 hash 变化。
**历史**：修复前失败/条纹证据在 `../manual-check/` 与 `../diagnosis/`；根因是回读源误为主 framebuffer。
