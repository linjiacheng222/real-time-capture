# AVR-M1-02 T1.1 工程脚手架与环境基线

> 报告类型：自动验证报告
> 关联任务：M1 / T1.1（I1.1.1~I1.1.5）
> 生成日期：2026-10-31
> 执行方：AI 执行端
> 验证环境：JDK 17.0.15 / Forge 47.4.23 / Gradle 8.14.5 / Windows PowerShell 5.1
> 结论：**PASS**
> 证据目录：`docs/evidence/T1.1/`

## 1. 目的与范围

验证工程脚手架（ForgeGradle/Mojmap/Java17）、Mixin/AT/refmap 基建、主类生命周期、硬依赖声明（Embeddium/ModernFix，D-MF-1）与红线 2（无 Connector）。覆盖 I1.1.1~I1.1.5。

## 2. 前置条件与环境

- 工程：`E:\捕获接口`，分支 `feat/T1.1` → main（提交 `f049531`，merge `1661632`）。
- `build.gradle`：MixinGradle 0.7.38 + Mixin AP 0.8.5（Sponge maven）、`mappings channel: 'official'`、dev 属性 `mixin.env.remapRefMap`/`refMapRemappingFile`/`captureapi.debug.mixin`。
- `gradle.properties`：`minModernfixVersion=5.18.0`（D-MF-1），dev 锚 5.27.89。
- 资源：`captureapi.mixins.json`、`META-INF/accesstransformer.cfg`（空模板）、`mods.toml`。

## 3. 验证过程（步骤、命令、关键输出）

1. **构建**：`.\scripts\build.ps1 -SkipClean` → BUILD SUCCESSFUL；24 项校验（当时 22 项）PASS。
2. **产物核对**：解包 `build\libs\captureapi-0.5.0.jar`：
   - `META-INF/mods.toml`（UTF-8，`displayName=捕获接口`、`authors=林嘉诚`、4 依赖、`loaderVersion="[47,)"`）；
   - `captureapi.mixins.json`（package `com.captureapi.core.mixin`，client `MinecraftMixin`）；
   - `captureapi.refmap.json`（含 SRG 映射 `run → m_91374_`）；
   - `META-INF/accesstransformer.cfg`；`MANIFEST.MF` 含 `MixinConfigs: captureapi.mixins.json`。
3. **依赖/红线 2**：`gradlew dependencies --configuration runtimeClasspath` 快照 304 行，关键词 connector/fabric-loader/quilt/fabric.mod/quilted **0 命中**；jar 21 条目无 fabric/quilt 元数据。
4. **dev 运行**：`.\scripts\run-client.ps1` → 33s 主菜单；三模组加载；日志出现 `[CaptureAPI] 主类构造完成`、`[CaptureAPI] 底座就绪`、`[CaptureAPI] Mixin 基建自检：Minecraft#run 注入生效`；无 FATAL/ERROR/crash、退出无残留。
5. **环境自检**：`.\scripts\verify-env.ps1` → 37 PASS/0 FAIL/0 WARN（T1.1 时点）。

## 4. 结果与证据

| # | 检查项 | 期望 | 实测 | 结论 | 证据 |
|---|---|---|---|---|---|
| 1 | 构建 | BUILD SUCCESSFUL | 是；产物 8040 bytes（T1.1 时点） | PASS | `docs/evidence/T1.1/build.log` |
| 2 | mods.toml 元数据 | UTF-8/中文/四依赖/loaderVersion | 全部满足；modernfix `[5.18.0,)` | PASS | `docs/evidence/T1.1/jar-*` |
| 3 | Mixin 基建 | mixins.json+AT+refmap+MixinConfigs | 齐备；refmap 含 SRG | PASS | `docs/evidence/T1.1/captureapi.*` |
| 4 | 主类生命周期 | `[CaptureAPI]` 日志 | 构造/就绪日志出现 | PASS | `run-client-summary.txt`、`run-client-key-lines.txt` |
| 5 | 红线 2 | 0 命中 Connector | 依赖快照 0；jar 无 Fabric/Quilt | PASS | `deps-runtimeClasspath.txt`、`jar-entries.txt` |
| 6 | dev 客户端 | 主菜单无崩溃 | 33s，无 FATAL/crash/残留 | PASS | `run-client-summary.txt` |
| 7 | 环境自检 | 0 FAIL | 37/0/0 | PASS | `verify-env-final.md` |

## 5. 判定标准与结论

- 全部自动项 PASS；Mixin 注入以 `Minecraft#run` HEAD 自检（dev 属性开启时 INFO，生产 DEBUG 静默）证明 refmap/AT 管线可用。
- 结论：**PASS**。破坏性用例「移除 embeddium 应报缺失前置」登记为 MS1 人工/收尾项（见 T1.1 验收记录 §遗留）。

## 6. 人工反馈区

不适用（自动验证）。破坏性依赖用例转人工：见 T1.1 验收记录；本项未单列 MVR（可选执行）。

## 7. 修复尝试记录（追加区）

- 尝试 1（2026-10-31）：`@Shadow` 生产 jar 在 named dev 崩溃 → 采用 fg.deobf named 变体（`prepare-dev-deps.ps1`）；复验 runClient PASS。
- 尝试 2（2026-10-31）：Mixin 类静态字段污染 Minecraft 日志器 → 日志迁移至 `CaptureMixinDiagnostics`；复验日志名正常。
- 尝试 3（2026-10-31）：`.ps1` 被编辑后丢 UTF-8 BOM 导致 PS5.1 解析失败 → 补 BOM + Parser 校验；脚本全部可执行。

## 8. 附件与证据索引

- 验收记录：`docs/T1.1-验收记录.md`；红线：`docs/红线核验记录.md` §红线 2
- 证据：`docs/evidence/T1.1/`（build.log、verify-env-final.md、deps-runtimeClasspath.txt、jar-entries.txt、jar-MANIFEST.MF、captureapi.mixins.json、captureapi.refmap.json、accesstransformer.cfg、run-client-*.txt）
