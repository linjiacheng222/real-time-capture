# P2.5 M9 专用服实测（dev 专用服，2026-11-01 22:41）

## 场景
- dev `runServer`（仅加载 serverComponent，客户端主模组不加载）离线模式；
- 客户端 `--quickPlayMultiplayer 127.0.0.1:25565` 加入，创建相机于玩家 +250 格；
- 服务端 TicketManager 新增申请/释放日志。

## 结果
| 阶段 | 服务端日志 | 结论 |
|---|---|---|
| 相机申请 | `[TICKETS] request ... accepted=109 owned=109 total=109` | ticket 受理 ✅ |
| 客户端断开 | `[TICKETS] releaseAll ... released=109 total=0` | 断线回收 ✅ |

证据：`tickets-request.txt`、`tickets-release.txt`。

## 判定
**P2.5 核心链路 PASS（dev 专用服）**：远程玩家相机 → 区块申请受理 → 断线回收归零。

## 限制/待用户
- 早期 dev 测试使用源码组件；2026-11-01 22:54 已补 **生产 Forge 专用服 + reobf server jar** 主链路 PASS（见上）；
- Permissions/单玩家/全局限额/多玩家并发矩阵未做；
- 生产环境仍建议按 `docs/专用服部署指南.md` 复核权限/限额配置。

## 追加：Ticket 限额路径（2026-11-01 22:45）

- 服务端 config `max_chunks_per_player=32`、`max_chunks_global=64`，相机 +250/请求 109 区块；
- 结果：客户端 `/health` → `remote_last_code=E_PLAYER_LIMIT`，`accepted=0`；**超限拒绝路径 PASS**；
- 证据：`remote-limit-health.json`；
- 注意：修改 `run/world/serverconfig/captureapi_server-server.toml` 必须无 BOM 写入（PowerShell 5.1 `Set-Content -Encoding UTF8` 带 BOM 会导致 TOML 解析失败，服务器崩溃）。

## 追加：**生产 Forge 专用服 + reobf server jar PASS**（2026-11-01 22:54）

- 使用官方 Forge 1.20.1-47.4.23 installer 安装独立生产专用服（目录 `E:\ForgeServerTest`），离线模式 25566；
- mods 仅放 `captureapi-0.5.0-server.jar`（reobf 产物）；dev 客户端连接；
- 结果：
  - 客户端握手 v1 OK；
  - `/health`：remote_ready=true，requests=1，accepted=113/230，code=OK；
  - 相机拉到远处帧（106,728B），画面为已加载地形（production-far-frame.jpg）；
  - 客户端断开：服务端 `[TICKETS] releaseAll ... released=113/230 total=0`；
- 证据：`production-server-health.json`、`production-server-tickets.txt`、`production-server-release.txt`、`production-near-frame.jpg`、`production-far-frame.jpg`；
- 结论：**P2.5/P1.3 生产专用服主链路 PASS**（reobf server jar + 握手 + ticket 申请/回收 + 远处画面）。Permissions/多玩家/全局限额矩阵仍待补。

