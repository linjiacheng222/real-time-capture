# P1.3 远程 MP 客户端申请侧（M9 通道）——2026-11-01

## 已实现（客户端）
- 新增 `RemoteCaptureClient`：与 `captureapi_server` 组件同 channel（`captureapi_server:main`）的 SimpleChannel；
- 登录后：
  - 单机 → 跳过（no-op）；
  - 远程 MP 且服务端注册通道 → 发送 `HandshakeRequest(v1)`；
  - 握手成功 → `CaptureChunkPlanner` 区块集合差分经 `ChunkRequest` 发送，移除区块经 `ChunkRelease`；
  - 服务端 `ServerResponse` 记录受理码/accepted；断线清理。
- `CaptureChunkPlanner` 重算后调用 `RemoteCaptureClient.onChunks`；
- `/health` 新增 `remote_ready`/`remote_requests`/`remote_accepted`/`remote_last_code`；
- 服务端组件补注册 `HandshakeResponse`（ID 4，PLAY_TO_CLIENT），修复原先“发送未注册消息”缺口。

## 已验证（单机 no-op）
- dev 单机：`remote_ready=false, requests=0, accepted=0`；未发送任何通道消息，不影响原功能；
- 证据：`remote-sp-health.json`。

## 远程 MP 专用服验证——未完成/受阻
尝试在 dev 启专用服：
- 本地无 `runServer` 配置，临时加配置后因 MixinGradle 给 server 运行注入客户端 `captureapi.mixins.json` 启动失败；
- 把 server jar 放入 dev 客户端 mods 时出现 `TicketType.m_9462_` NoSuchMethodError（SRG server jar 不应加载到 named 客户端；也提示该 jar 的 TicketType 映射需在真实专用服复核）；
- 已回退 build.gradle，不影响正常构建。

## 用户专用服最小测试操作单
1. 专用服（Forge 47.4.x + MC1.20.1）mods 放 **`captureapi-0.5.0-server.jar`**（不要放客户端 jar）；
2. 客户端 PCL 实例放最新 `captureapi-0.5.0.jar`（含本次申请侧）；
3. 客户端加入服务器，进入世界后创建相机（`scripts\s1-capture-check.ps1` 或控制指令）；
4. 检查：
   - 客户端日志应出现 `[REMOTE] 已发送 M9 握手` / `握手响应 supported=true`；
   - `/health` 的 `remote_ready=true`，相机移动后 `remote_requests`/`remote_accepted` 增长；
   - 服务端日志应记录 ticket 受理；相机移到视距外时画面远处内容出现；
5. 若失败，回传客户端/服务端日志尾部和 `/health` 五项 remote 字段。

## 判据
远程 MP 下区块申请受理并实际加载远处内容；当前仅有客户端代码 + 单机 no-op 证据，**远程 MP 未验收**。

## 补充：dev 专用服联调 **PASS**（2026-11-01 22:29）

- dev 增补 `runServer` 配置（仅加载 serverComponent，移除客户端 mixin 注入），离线模式启动专用服；
- 客户端 `--quickPlayMultiplayer 127.0.0.1:25565` 加入；
- 结果：
  - 客户端日志：`[REMOTE] 已发送 M9 握手（协议 v1）` → `握手响应 supported=true, serverProtocol=1, msg=OK`；
  - `/health`：`remote_ready=true, remote_requests=1, remote_accepted=109, remote_last_code=OK`；
  - 相机置于 +200 格，拉帧成功（38,319B），画面出现**已加载的远处地形**（`remote-mp-frame.jpg`）；
- 结论：**P1.3 基础远程 MP 通路 PASS（dev 专用服）**；生产专用服/权限/限额矩阵与 reobf server jar 仍待用户环境复核。

