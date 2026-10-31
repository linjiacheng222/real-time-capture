# 捕获接口（Capture Interface）v0.5 —— 项目根

Minecraft **1.20.1 Forge** 客户端模组：游戏内自定义相机位下，流式输出视野画面与环境音频（默认全程内存流转、低延迟、高稳定；文件/录像为显式请求的可选落盘出口）。

## 当前状态

- 工程根：`E:\捕获接口\`（ForgeGradle 6 + Mojmap + Java 17；Gradle 8.14.5 wrapper，华为云镜像）。
- 构建：2026-10-28 首次 `gradlew build` 通过；**2026-10-31 T1.1~T1.3 完成**：`scripts\build.ps1` clean build PASS（22/22 校验，含 Mixin/AT/refmap/红线 2）。
- 代码：**T1.1~T1.3 已实施**（M1 首批 + 核心设施）——
  `CaptureInterface`（构造器注入 `FMLJavaModLoadingContext`）、`core.ClientSetupHandler`（CLIENT setup 订阅）、
  `core.thread.GlThreadGuard`（GL 主线程守卫）、`core.thread.LatestValueChannel`/`MpscRingBuffer`（跨线程通道）、
  `core.thread.CaptureRenderHook`（AFTER_LEVEL 事件骨架 + M2 监听器扩展点）、
  `core.resource.WorldResourceManager`（两级世界生命周期资源管理/幂等销毁/统计）+ `WorldLifecycleHandler`（LoggingIn/LoggingOut/LevelUnload/tick 兜底）；
  Mixin/AT/refmap 基建（MixinGradle 0.7.38 + Mixin AP 0.8.5）。业务功能按 M-T-I 树继续（下一任务 T1.4）。
- dev 依赖：`run/mods/` 放 named 变体（`embeddium-0.3.31…dev.jar`、`modernfix-5.27.89…dev.jar`）；生产 jar 存 `run/mods-original/`（D-BUILD-1/D-DEV-1）。
- 验收证据：`docs/T1.1-验收记录.md`、`docs/T1.2-T1.3-验收记录.md`、`docs/红线核验记录.md`、`docs/线程边界规范.md`、`docs/evidence/`（T1.1 / T1.2-T1.3）。
- Git 已安装：`E:\Tools\Git`（MinGit 2.56.0；`git.cmd` shim 已入 PATH）；FFmpeg 已安装：`E:\Tools\ffmpeg\bin\ffmpeg.exe`（7.1 essentials，含 libx264/AAC）。

## 构建

```bat
set JAVA_HOME=C:\Users\26217\AppData\Roaming\.minecraft\runtime\java-runtime-beta
gradlew.bat build
gradlew.bat runClient
```

## 协议

- 本模组：**MIT License**（`licenses/LICENSE`）。
- 第三方协议汇总与复用规则：`licenses/THIRD-PARTY-NOTICES.md`（含全部参考模组协议原文）。

## 相关目录

- 规划文档：`E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test\实时捕获\实时捕获项目规划（草稿）\实时捕获模组落地计划\`
- 原始资料：`E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test\实时捕获\实时捕获项目规划（草稿）\参考资料\`
- 参考源码仓库：`E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test\实时捕获\参考内容\源码仓库\`

## 开发环境（2026-10-30 配置）

- 基线文档：`docs/开发环境基线.md`（版本矩阵、固定路径、脚本用法、校验结果表、已知问题、故障排查、后续维护）。
- JDK 固定：`E:\Tools\jdk17`（OpenJDK 17.0.15，自 Mojang `java-runtime-beta` 复制；用户级 `JAVA_HOME`/`PATH` 已写入）。
- 一键脚本（PowerShell 5.1/7 双兼容，脚本内自行设置 `JAVA_HOME`，不依赖调用者环境）：
  - `. .\scripts\dev-env.ps1` 激活当前会话（`-Persist` 写入用户级环境变量）
  - `.\scripts\verify-env.ps1` 环境自检（PASS/FAIL 汇总表，失败非零退出）
  - `.\scripts\build.ps1` clean build + 产物/`mods.toml` 元数据校验（`-SkipClean` 增量、`-Offline` 离线）
  - `.\scripts\run-client.ps1` 启动 dev 客户端并轮询到主菜单，结束清理进程树（`-TimeoutMinutes` 超时）
  - `.\scripts\install-to-instance.ps1` 复制 jar 到 PCL 实例 mods（`-DryRun` / `-Remove`）
  - `.\scripts\prepare-dev-deps.ps1` dev 依赖准备：把生产 jar 重映射为 named 变体放入 `run\mods\`（`-Offline`/`-Download`）
- dev 依赖说明（技术修正，需用户知悉）：dev 客户端是 Mojmap named 环境，`run\mods\` 必须放 named 变体
  （`embeddium-0.3.31_mapped_official_1.20.1-dev.jar`、`modernfix-5.27.89_mapped_official_1.20.1-dev.jar`）；
  原始生产 jar 保存在 `run\mods-original\`（Forge 不加载）。升级 Embeddium/ModernFix 后重跑
  `scripts\prepare-dev-deps.ps1`。直接放生产 jar 会导致 `@Shadow ... m_5601_ ... was not located` 崩溃。
- 实测结果（2026-10-30 环境基线；2026-10-31 T1.1~T1.3 复测）：`verify-env.ps1` **38 PASS / 0 FAIL / 0 WARN**；clean build PASS（22/22 校验）；`--offline build` PASS；`runClient` 30~33s 到达主菜单（Forge 47.4.23 + Embeddium 0.3.31 + ModernFix 5.27.89 + captureapi），
  无 crash report、无 FATAL、退出后无残留 java；dev 自检 `[CaptureAPI] 核心自检 PASS`（GL 守卫/通道/资源管理器）；
  世界内进出/渲染帧计数/听感为人工验收项。
