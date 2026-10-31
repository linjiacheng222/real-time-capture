# MVR-M1-03 配置热重载 `/mfrc`（ModernFix 环境）

> 报告类型：人工校验报告
> 关联任务：M1 / T1.5（I1.5.3 配置加载与重载、I1.5.4 参数门面）
> 创建日期：2026-10-31
> 预期执行人：用户（D-HUMAN-1）
> 状态：待人工填写（自动验证已确认原因：ModernFix 拦截 Forge 自动重载，登记 W-103）
> 关联自动报告：AVR-M1-04（Loading PASS；Reloading PARTIAL）
> 证据提交位置：`docs/evidence/人工校验/`（建议）

## 1. 目的与范围

验证 TOML 修改后经 ModernFix 的 `/mfrc` 命令触发配置重载，`ModConfigEvent.Reloading` 回调出现、`CaptureParams` 快照刷新且新值生效。对应 I1.5.3 验收②（自动验证受环境限制未完成）。

## 2. 前置条件

- 环境：客户端保持运行（进入一个世界，命令需在有玩家/世界上下文时执行）；
- 依赖：ModernFix 5.27.89（本项目强制依赖，`/mfrc` 由 ModernFix 提供）；
- 配置：`run/config/captureapi-client.toml`（先备份）；建议把 `fps_max` 改为 `25` 或 `clock_logging=true` 便于观察；
- 日志：`run/logs/latest.log`。

## 3. 操作步骤

1. 启动 dev 客户端，进入任意单人世界（确保 `/mfrc` 可用；可先用 `/help mfrc` 确认命令存在）；
2. 备份 `run/config/captureapi-client.toml`，记录当前值（如 `fps_max = 30`、`clock_logging = false`）；
3. 在外部编辑器把 `fps_max` 改为 `25`（或把 `clock_logging` 改为 `true`）并保存；
4. 在游戏内执行 `/mfrc`；
5. 观察 `latest.log`：
   - 期望出现 `[CaptureAPI] 配置重载事件（Reloading）：captureapi-client.toml`；
   - 紧接着 `[CaptureAPI] 参数快照刷新（Reloading）：fps_max=25, …`；
   - 若开启 `clock_logging=true`，重载后每约 1s 出现 `[CaptureAPI] 时钟自测：…`；
6. 如需验证恢复：把 TOML 改回 `fps_max=30`，再次 `/mfrc` 并确认刷新为 30；
7. 保存关键日志行与截图，填写 §4。

## 4. 结果记录（人工填写）

| # | 检查项 | 期望 | 实际 | 证据 | 备注 |
|---|---|---|---|---|---|
| 1 | `/mfrc` 命令可用 | 命令存在并执行 | | | |
| 2 | Reloading 事件 | `配置重载事件（Reloading）` | | | |
| 3 | 快照刷新 | `参数快照刷新（Reloading）` 且值改变 | | | 记录 fps_max 新旧值 |
| 4 | 门面取值生效 | 后续行为/日志反映新值 | | | |
| 5 | 恢复默认值 | 再次 `/mfrc` 后值回 30 | | | |
| 6 | 无异常 | 无配置解析异常/崩溃 | | | |

## 5. 理想反馈与判定标准

- 执行 `/mfrc` 后 1~3s 内出现：
  - `[CaptureAPI] 配置重载事件（Reloading）：captureapi-client.toml`
  - `[CaptureAPI] 参数快照刷新（Reloading）：fps_max=25, …`（或 `clock_logging=true`）
- 无 `ConfigLoadingException`/解析异常/崩溃；
- 通过条件：2~4 满足即通过；若 `/mfrc` 后仍无 Reloading 日志 → 判失败并保留完整日志（后续修复追加于 §7）。

## 6. 人工反馈区（留空）

> 请填写：执行人 / 日期 / 总体结论（通过 / 失败 / 部分通过）/ 是否出现 Reloading 与参数刷新 / 异常描述 / 附件清单。
> 若失败，请附：ModernFix 版本、`/mfrc` 命令回显、`latest.log` 相关行、是否安装了其他配置类模组。

（本区域留空）

## 7. 修复尝试记录（追加区）

> 仅失败/异常时追加：尝试 N（日期）+ 现象 / 假设 / 修复动作 / 复验结果 / 结论。
> 已知背景（供参考）：自动验证阶段修改 TOML 不触发 Reloading，ModernFix `NightConfigFixer` 接管 NightConfig FileWatcher 并提示 `/mfrc`（W-103）。

（本区域留空）

## 8. 附件与索引

- 关联自动报告：`docs/自动验证报告/AVR-M1-04-T1.4-T1.5-时间基准与参数框架.md`
- 验收记录：`docs/T1.4-T1.5-验收记录.md` §6、§10；规划 W 表 W-103
- 代码：`core/config/ModConfigEvents.java`、`CaptureParams.java`
