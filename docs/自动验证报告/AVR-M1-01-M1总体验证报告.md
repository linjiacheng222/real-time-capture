# AVR-M1-01 M1 总体验证报告

> 报告类型：自动验证报告
> 关联任务：M1 / T1.1~T1.7（24 个 I 全覆盖）
> 生成日期：2026-10-31
> 执行方：AI 执行端（DeepSeek Harness Lead）
> 验证环境：Windows 10/11；JDK 17.0.15（`E:\Tools\jdk17`）；Forge 47.4.23（发布下限 47.3.0）；Gradle wrapper 8.14.5；PowerShell 5.1；Embeddium 0.3.31 + ModernFix 5.27.89（dev named 变体）
> 结论：**PASS**（自动可判定部分）；人工项见 `docs/人工校验报告/`
> 证据目录：`docs/evidence/T1.1/`、`T1.2-T1.3/`、`T1.4-T1.5/`、`T1.6-T1.7/`

## 1. 目的与范围

- 验证 M1「工程底座与 Forge 适配」全部 T 级任务的构建、运行、自检、合规与映射一致性；
- 覆盖：环境基线（T1.1）、世界资源与线程边界（T1.2/T1.3）、统一时间基准与参数框架（T1.4/T1.5）、版权合规与映射对照（T1.6/T1.7）；
- 不覆盖（人工/后续）：真实世界进出 10 轮压测、AFTER_LEVEL 世界内帧计数、`/mfrc` 配置热重载、听感/帧率体感、Configured 兼容、M2~M8 功能。

## 2. 前置条件与环境

- 工程根：`E:\捕获接口`（git 分支 `main`，最终提交 `1f68d4e`，本地未推送，D-GIT-1）；
- 固定 JDK：`E:\Tools\jdk17`（OpenJDK 17.0.15；`JAVA_HOME` 用户级已持久化）；
- dev 依赖：`run/mods/` 为 named 变体（`embeddium-0.3.31_mapped_official_1.20.1-dev.jar`、`modernfix-5.27.89_mapped_official_1.20.1-dev.jar`），生产 jar 备份在 `run/mods-original/`；
- 配置载体：`run/config/captureapi-client.toml`（ForgeConfigSpec CLIENT，四 section）；
- 规划文档：`E:\PCL\...\实时捕获项目规划（草稿）\实时捕获模组落地计划\`。

## 3. 验证过程（步骤、命令、关键输出）

### 3.1 环境自检（verify-env）

1. 命令：`.\scripts\verify-env.ps1 -MarkdownOut .\docs\evidence\<批次>\verify-env-final.md`
2. 过程：脚本自动设置 `JAVA_HOME`，检查 JDK/Gradle/Forge 缓存、`gradle.properties` 10 项基准、mods.toml 四依赖、run/mods named 变体、构建产物、Mixin/AT/refmap、dev 自检日志、配置骨架、网络（重试 3 次）、硬件与端口。
3. 关键输出：T1.2-T1.3 批次 **38 PASS/0 FAIL/0 WARN**；T1.4-T1.5 批次增至 **39 PASS/0 FAIL/0 WARN**（新增「配置骨架与快照日志」）；最终复检（`-SkipNetwork -SkipGradle`）29 PASS/0 FAIL/0 WARN/10 SKIP。

### 3.2 构建（build.ps1）

1. 命令：`.\scripts\build.ps1`（clean build）与 `.\scripts\build.ps1 -SkipClean`（增量）。
2. 过程：`gradlew clean build` → 解包 `build\libs\captureapi-0.5.0.jar`，校验 24 项：Gradle 成功、jar 非空、mods.toml UTF-8/中文元数据、loaderVersion、四依赖版本区间（D-MF-1 modernfix `[5.18.0,)`）、主类、Mixin 配置/package/MinecraftMixin、AT、refmap 含 SRG、`MixinConfigs`、无 Fabric/Quilt、**红线 4 时钟静态扫描**、**常数白名单 12 项**。
3. 关键输出：连续多轮 **PASS=24 FAIL=0**；最终 HEAD 增量构建耗时约 73s、产物 `captureapi-0.5.0.jar` 46692 bytes。

### 3.3 dev 客户端（run-client.ps1）

1. 命令：`.\scripts\run-client.ps1`（自动轮询日志至主菜单、结束 taskkill /T）。
2. 过程：启动 Forge 47.4.23 dev client → 轮询 `latest.log`/`debug.log` → 校验标记（Forge、captureapi、Embeddium、ModernFix、GL 后端、OpenAL、主菜单）→ 清理进程树并校验无残留。
3. 关键输出（T1.2/T1.3、T1.4/T1.5 批次）：
   - **30~33s 到主菜单**，三模组加载，无 FATAL/crash/残留；
   - `[CaptureAPI] 核心自检 PASS（renderThread=true，channel 1000 次发布/读取=true，clock=true，config=true，资源管理器=true）`；
   - `参数快照建立（Loading）：fps_max=30, …, net_port=25580, …`；
   - 新标记「配置骨架快照建立（T1.5，dev）」= YES。
4. 注意：dev 自检会输出 1 条**预期** GL 线程违规 ERROR（负例）；生产不启用该属性。

### 3.4 参数与越界行为（T1.5）

1. 启动生成 `run/config/captureapi-client.toml`：四 section、15 参数、范围注释齐全。
2. 将 `fps_max` 改为 500 后重启：Forge 日志 `Configuration file … is not correct. Correcting`，文件被修正为 **240**（范围上限），快照值=240（证据 `docs/evidence/T1.4-T1.5/config-correction-*`）。
3. `clock_logging=true` 后实测每秒输出 `时钟自测：nowNanos=…，间隔≈1037/1042ms`。

### 3.5 版权与映射（T1.6/T1.7）

1. 直接读取参考 jar/仓库 LICENSE/元数据：DebugBridge MIT 逐字一致；Record-able jar（`Record-able contributors`）与上游仓库（`Minewind's Jo Eusebe`）双版本留档；Pixelshot jar `mods.toml` 实证 MPL-2.0 + fork 署名；Mineshot Revived 仓库 LICENSE=GPL-3.0。
2. 映射全链校验：`python scripts\verify-mappings.py --forge-jar <forge recomp jar> --evidence ...` → 下载 Yarn/intermediary/Mojang mappings（缓存 `build/mappings-cache/`，不入库），全链 Yarn→intermediary→official→Mojmap + Forge 类存在性，**PASS=12 FAIL=0**。
3. 关键映射差异：`SoundEngine→com.mojang.blaze3d.audio.Library`、`WorldRenderer→LevelRenderer`、`MatrixStack→com.mojang.blaze3d.vertex.PoseStack`、`MinecraftClient→Minecraft`。

### 3.6 规划文档自检

- 命令：`python .workbuddy\fill_check.py --all`（规划目录）
- 输出：185 个 I，严格完全填充 133 个，**告警文件 0 个**。

## 4. 结果与证据

| # | 检查项 | 期望 | 实测 | 结论 | 证据 |
|---|---|---|---|---|---|
| 1 | 环境自检 | 0 FAIL | 39 PASS/0 FAIL/0 WARN（最终复检 29/0/0/10 SKIP） | PASS | `docs/evidence/T1.4-T1.5/verify-env-final.md` |
| 2 | 构建与元数据 | 24/24 | 24/24 PASS | PASS | `docs/evidence/*/build.log` |
| 3 | dev 客户端 | 主菜单+无崩溃 | 30~33s 主菜单、无 FATAL/crash/残留 | PASS | `docs/evidence/*/run-client-summary.txt` |
| 4 | M1 核心自检 | 全 true | renderThread/channel/clock/config/resource 全 PASS | PASS | `docs/evidence/T1.2-T1.3/run-client-key-lines.txt`、`T1.4-T1.5/` |
| 5 | 配置骨架 | 四 section+15 参数 | 生成且注释/范围正确 | PASS | `docs/evidence/T1.4-T1.5/captureapi-client-default.toml` |
| 6 | 越界修正 | 明确回退/修正 | fps_max=500→240 + WARN | PASS | `docs/evidence/T1.4-T1.5/config-correction-*` |
| 7 | 时钟纪律 | 白名单外 0 命中 | 0 命中；单调性自检 PASS | PASS | `build.ps1` 检查项、`T1.4-T1.5` 日志 |
| 8 | 版权合规 | 实物核验 | 双版权留档、MPL/GPL 分类 | PASS | `docs/evidence/T1.6-T1.7/license-verification.txt` |
| 9 | 映射全链 | 0 FAIL | PASS=12 FAIL=0 | PASS | `docs/evidence/T1.6-T1.7/verify-mappings.txt` |
| 10 | 规划文档 | 0 告警 | 0 告警 | PASS | fill_check 输出 |
| 11 | git 状态 | 工作树干净 | 3 分支保留、main 干净、未推送 | PASS | `git log/status` |

## 5. 判定标准与结论

- 判定标准：全部自动检查项 PASS；无 FATAL/crash；证据可复现；人工不可判定项已转交人工报告。
- 结论：**PASS**。M1 自动可验证部分全部通过；残余风险与人工项见 §6/§8。

## 6. 人工反馈区

不适用（自动验证）。M1 人工项见：
- `docs/人工校验报告/MVR-M1-01-世界生命周期四路径与10轮压测.md`
- `docs/人工校验报告/MVR-M1-02-AFTER_LEVEL帧计数与主画面体感.md`
- `docs/人工校验报告/MVR-M1-03-配置热重载-mfrc.md`
- `docs/人工校验报告/MVR-M1-04-Configured兼容（附加）.md`
- `docs/人工校验报告/MVR-M1-05-主观体感基线.md`

## 7. 修复尝试记录（追加区）

- 2026-10-31 T1.1 阶段：`RenderSystem.recordRenderCall` 参数类型由 Runnable 修正为 `task::run`（编译期错误→修复后 24/24 PASS）。
- 2026-10-31 T1.1 阶段：Mixin 类静态字段污染 Minecraft 日志器 → 自检日志迁移至独立 helper 类（复验日志正常）。
- 2026-10-31 T1.5 阶段：自检对配置默认值过严（fps_max 被用户修正后 FAIL）→ 改为范围校验（复验 PASS）。
- 2026-10-31 T1.6/T1.7 阶段：mapping 脚本 namespace 顺序/斜杠比较问题 → 修正后 12/12 PASS。

## 8. 附件与证据索引

- 验收记录：`docs/T1.1-验收记录.md`、`docs/T1.2-T1.3-验收记录.md`、`docs/T1.4-T1.5-验收记录.md`、`docs/T1.6-T1.7-验收记录.md`
- 红线核验：`docs/红线核验记录.md`；环境基线：`docs/开发环境基线.md`
- 规范/对照：`docs/线程边界规范.md`、`docs/反编译分析工作流.md`、`docs/移植代码来源标注规范.md`、`docs/附录A-映射对照表.md`、`docs/映射校验流程.md`
- 提交：`f049531`/`1661632`、`a34b300`/`a00f5ea`、`25f8b28`/`96ab694`、`592628a`/`35a2332`、`1f68d4e`
