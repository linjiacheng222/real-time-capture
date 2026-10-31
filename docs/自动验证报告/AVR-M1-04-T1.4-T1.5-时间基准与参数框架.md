# AVR-M1-04 T1.4+T1.5 时间基准与参数框架

> 报告类型：自动验证报告
> 关联任务：M1 / T1.4（I1.4.1~I1.4.2）、T1.5（I1.5.1~I1.5.4）
> 生成日期：2026-10-31
> 执行方：AI 执行端
> 验证环境：同 AVR-M1-01；dev 自检属性开启
> 结论：**PASS（自动部分）**；配置热重载受 ModernFix 拦截，转人工 MVR-M1-03
> 证据目录：`docs/evidence/T1.4-T1.5/`

## 1. 目的与范围

验证红线 4（唯一时间基准 + 时钟纪律）与红线 1 的机制基础（ForgeConfigSpec 四 section、常数白名单、Loading/Reloading 事件、CaptureParams 快照门面）。覆盖 I1.4.1~I1.5.4。

## 2. 前置条件与环境

- 分支 `feat/T1.4-T1.5` → main（提交 `25f8b28`，merge `96ab694`）。
- 新增：`core.time.CaptureClock`/`LogTimeConverter`/`ClockTickLogger`；`core.config.CaptureConfig`/`CaptureParams`/`ModConfigEvents`/`ReloadScope`；`core.constant.CaptureConstants`（12 项白名单）。
- 构造器注册 `captureapi-client.toml`；`build.ps1` 新增红线 4 静态扫描 + 常数白名单检查（24 项）。

## 3. 验证过程（步骤、命令、关键输出）

1. **构建 + 静态扫描**：`.\scripts\build.ps1` → 24/24 PASS；静态扫描遍历 `src/main/java`，禁止 `System.currentTimeMillis`/`Instant.now`/`System.nanoTime`/`getGameTime`，白名单仅 `CaptureClock`/`LogTimeConverter` → **白名单外 0 命中**。
2. **时钟自检**：`captureapi.debug.selftest` 下 `CaptureClock.selfTestMonotonic(1000)` PASS；`clock_logging=true` 时 `ClockTickLogger` 每秒输出 `时钟自测`，实测间隔约 1037/1042ms。
3. **配置生成**：启动后生成 `run/config/captureapi-client.toml`（[video]/[audio]/[server]/[log]，15 参数，注释与 Range 齐全）；debug.log 显示 `Loaded/ Watching TOML config file`。
4. **加载事件**：`配置加载事件（Loading）` + `参数快照建立（Loading）：fps_max=30, video_resolution=1280x1024, …, net_port=25580, …`。
5. **门面/自检**：`核心自检 PASS（… clock=true，config=true …）`；`配置快照校验：fps_max=30, video_resolution=1280x1024, net_port=25580, sample_rate=44100, channels=2, bit_depth=16`。
6. **越界修正**：手动将 `fps_max` 设为 500 → 重启：Forge WARN `Configuration file … is not correct. Correcting`，文件修正为 **240**（上限），快照值=240。
7. **配置诊断开关联动**：`clock_logging=true` → 秒级 `时钟自测` 日志；`log_gl_thread_violation/log_frame_events/log_resource_lifecycle` 由 snapshot 应用（代码路径+默认值核对）。
8. **热重载观测（失败/受限）**：运行中修改 TOML 后无 `Reloading` 事件；ModernFix 输出 `Please use /mfrc to reload any changed mod config files`；排查发现 ModernFix `NightConfigFixer` 接管 FileWatcher → 自动重载被拦截（登记 W-103），转人工 MVR-M1-03。
9. **环境自检**：`verify-env.ps1` → 39 PASS/0 FAIL/0 WARN（新增「配置骨架与快照日志」）。

## 4. 结果与证据

| # | 检查项 | 期望 | 实测 | 结论 | 证据 |
|---|---|---|---|---|---|
| 1 | 构建 | 24/24 | 24/24 PASS | PASS | `docs/evidence/T1.4-T1.5/build.log` |
| 2 | 时钟静态扫描 | 白名单外 0 | 0 命中 | PASS | build 输出 |
| 3 | 时钟单调性 | 非递减 | selfTest PASS；抽样间隔正常 | PASS | 核心自检日志 |
| 4 | 配置骨架 | 四 section/15 参数 | 生成正确、注释/范围齐全 | PASS | `captureapi-client-default.toml` |
| 5 | Loading 快照 | 日志建立 | 出现完整参数快照日志 | PASS | `run-client-key-lines.txt` |
| 6 | 越界修正 | 修正并记录 | fps_max 500→240 + WARN | PASS | `config-correction-*` |
| 7 | 门面 getter | 返回快照值 | 配置快照校验行一致 | PASS | 日志 |
| 8 | 诊断开关 | clock_logging 生效 | 秒级时钟自测日志 | PASS | `config-correction-latest.log` |
| 9 | Reloading 自动触发 | 出现刷新日志 | **未触发**（ModernFix 拦截） | PARTIAL | `reload-test-latest.log`；W-103 |
| 10 | 环境自检 | 0 FAIL | 39/0/0 | PASS | `verify-env-final.md` |

## 5. 判定标准与结论

- 除「Reloading 自动触发」受强制依赖 ModernFix 拦截外，全部自动项 PASS；该限制已登记 W-103 并转人工 `/mfrc` 验证。
- 结论：**PASS（自动部分）**；热重载闭环为 PARTIAL（人工 MVR-M1-03）。

## 6. 人工反馈区

不适用（自动验证）。人工项：
- MVR-M1-03（`/mfrc` 热重载）；
- MVR-M1-04（Configured 兼容，附加）。

## 7. 修复尝试记录（追加区）

- 尝试 1：`defineInList` 返回 `ConfigValue<Integer>` 而非 `IntValue` → 字段类型修正；编译 PASS。
- 尝试 2：自检对配置默认值硬编码（fps_max==30）导致用户修正后 FAIL → 改为范围校验（1~240 等）；复验 PASS。
- 尝试 3：日志文件编码为 GBK，UTF-8 读取导致中文匹配失败 → 读取改为 `FileShare.ReadWrite` + UTF-8 strict→GBK fallback；脚本复验 PASS。
- 热重载：尝试直接改 TOML → 未触发；查 ModernFix 字节码确认其 `NightConfigFixer` 拦截，未修改依赖；登记 W-103 并转人工。

## 8. 附件与证据索引

- 验收记录：`docs/T1.4-T1.5-验收记录.md`；W-103：规划 W 表
- 证据：`docs/evidence/T1.4-T1.5/`（build.log、verify-env-final.md、run-client-summary.txt、run-client-key-lines.txt、captureapi-client-default.toml、captureapi-client-corrected.toml、config-correction-latest.log、config-correction-debug.log、reload-test-latest.log）
