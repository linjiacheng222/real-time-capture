# AVR-M1-03 T1.2+T1.3 世界资源与线程边界

> 报告类型：自动验证报告
> 关联任务：M1 / T1.2（I1.2.1~I1.2.3）、T1.3（I1.3.1~I1.3.4）
> 生成日期：2026-10-31
> 执行方：AI 执行端
> 验证环境：同 AVR-M1-01；dev 自检属性 `captureapi.debug.selftest=true`
> 结论：**PASS（自动部分）**；世界内人工项见 MVR-M1-01/02
> 证据目录：`docs/evidence/T1.2-T1.3/`

## 1. 目的与范围

验证红线 3 的两项制度保障：GL 线程守卫与跨线程通道、世界生命周期资源管理器（两级/幂等/统计）、AFTER_LEVEL 渲染事件骨架、线程边界规范。覆盖 I1.2.1~I1.3.4。

## 2. 前置条件与环境

- 工程分支 `feat/T1.2-T1.3` → main（提交 `a34b300`，merge `a00f5ea`）。
- 新增类：`core.thread.GlThreadGuard`/`LatestValueChannel`/`MpscRingBuffer`/`CaptureRenderHook`；`core.resource.WorldResourceManager`/`ManagedResource`/`ManagedGlResource`/`WorldResourceStats`/`WorldLifecycleHandler`/`NoopProbeResource`/`ResourceSelfTest`；`core.CoreSelfTest`。
- 自检入口：`ClientSetupHandler` → `CoreSelfTest.scheduleIfEnabled()`（`captureapi.debug.selftest=true`）。

## 3. 验证过程（步骤、命令、关键输出）

1. **编译/构建**：`gradlew build` → SUCCESS（`RenderSystem.recordRenderCall` 需 `task::run` 适配，修正后通过）。
2. **自检流程（dev）**：
   - 非渲染线程负例：`captureapi-selftest-nonrender` 调用 `GlThreadGuard.checkGlThread("selftest-nonrender-expected-violation")` → 输出一条**预期** GL 违规 ERROR（含操作名/线程名）；
   - 渲染线程正例（`RenderSystem.recordRenderCall` 调度）：守卫通过；`LatestValueChannel` 发布 1000 次/读取/清空；`ResourceSelfTest` 独立管理器实例：注册 2 世界资源 → `onWorldJoin` 创建 → `get()` → `onWorldLeave` 销毁 → 重复 `onWorldLeave` 幂等 → 销毁后 `get()` 抛 `IllegalStateException`。
3. **日志验证**：`[CaptureAPI] 核心自检 PASS（renderThread=true，channel 1000 次发布/读取=true，资源管理器=true）`；`资源管理器自检 PASS`；统计 `created=2, destroyed=2, failedCreate=0, aliveWorld=0`。
4. **run-client**：`.\scripts\run-client.ps1` → 30s 主菜单；可选标记「M1 核心自检 PASS」= YES；无 FATAL/致命/crash/残留；`run-client.ps1` 新增该标记、`verify-env.ps1` 新增「M1 核心自检日志」检查。
5. **环境自检**：`verify-env.ps1` → 38 PASS/0 FAIL/0 WARN。

## 4. 结果与证据

| # | 检查项 | 期望 | 实测 | 结论 | 证据 |
|---|---|---|---|---|---|
| 1 | GL 守卫正例 | 渲染线程通过 | 通过（无违规日志） | PASS | `docs/evidence/T1.2-T1.3/run-client-key-lines.txt` |
| 2 | GL 守卫负例 | 产生违规日志 | 1 条预期 ERROR（线程/操作名） | PASS（预期） | 同上 |
| 3 | LatestValueChannel | 1000 次发布/读取一致 | true；clear 语义正确 | PASS | 核心自检日志 |
| 4 | 资源生命周期 | 创建=1/销毁=0/幂等 | 自检 PASS；统计归零 | PASS | `资源管理器自检 PASS` |
| 5 | `get()` 快速失败 | 未创建/销毁后抛异常 | `IllegalStateException`（自检覆盖） | PASS | 自检代码路径+日志 |
| 6 | 生命周期挂接 | LoggingIn/Out+LevelUnload+tick | 事件订阅编译通过；非渲染线程投递路径实现 | PASS（编译） | 代码评审 |
| 7 | AFTER_LEVEL 骨架 | 事件订阅可用 | 编译/加载无异常；世界内帧计数为人工项 | PASS（骨架） | 人工 MVR-M1-02 |
| 8 | 线程规范 | 文档含四线程表 | `docs/线程边界规范.md` | PASS | 文档 |
| 9 | run-client | 主菜单+自检 PASS | 30s；标记 YES；无崩溃 | PASS | `docs/evidence/T1.2-T1.3/run-client-summary.txt` |
| 10 | 环境自检 | 0 FAIL | 38/0/0 | PASS | `verify-env-final.md` |

## 5. 判定标准与结论

- 自动可判定项全部 PASS；世界内真实生命周期/每帧回调无法程序自动判定，转人工（MVR-M1-01/02）。
- 结论：**PASS（自动部分）**；注意 1 条预期 ERROR 不代表故障。

## 6. 人工反馈区

不适用（自动验证）。人工项：MVR-M1-01（四路径/10 轮）、MVR-M1-02（AFTER_LEVEL 帧计数）。

## 7. 修复尝试记录（追加区）

- 尝试 1：`RenderSystem.recordRenderCall(Runnable)` 编译失败 → 改 `recordRenderCall(task::run)`；构建 PASS。
- 尝试 2：`NoopProbeResource` 为 final 无法被子类覆写 key → 去掉 final 供自检第二个资源使用；自检 PASS。
- 尝试 3：`WorldResourceManager.shutdown()` 在 JVM 关闭线程可能非渲染线程 → 非渲染线程跳过 GL 销毁并 WARN；复验无异常。

## 8. 附件与证据索引

- 验收记录：`docs/T1.2-T1.3-验收记录.md`（§8 人工清单）；线程规范：`docs/线程边界规范.md`
- 证据：`docs/evidence/T1.2-T1.3/`（build.log、verify-env-final.md、run-client-key-lines.txt、run-client-summary.txt、src-inventory.txt）
