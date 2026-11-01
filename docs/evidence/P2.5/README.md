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
- 本测试使用 dev 命名环境下的 serverComponent 源码，不是生产 Forge 专用服 + reobf `captureapi-0.5.0-server.jar`；
- Permissions/单玩家/全局限额/多玩家并发矩阵未做；
- 生产环境请按 `docs/evidence/P1.3/README.md` 使用 server jar 在真实专用服复核。
