# verify-env 校验结果（2026-10-31 21:09:03）

| 类别 | 检查项 | 结果 | 耗时(s) | 详情 |
| --- | --- | --- | --- | --- |
| 运行时 | PowerShell 可用 | PASS | 0.01 | 版本 5.1.26100.9711（Desktop）；注意：本机为 Windows PowerShell 5.1，脚本按 5.1/7 双兼容编写 |
| 运行时 | JDK17 固定路径 E:\Tools\jdk17 | PASS | 0.02 | 完整 JDK（193 个文件，86.9 MB） |
| 运行时 | java -version = 17.0.15 | PASS | 0.1 | openjdk version "17.0.15" 2025-04-15 LTS |
| 运行时 | javac -version = 17.0.15 | PASS | 0.19 | javac 17.0.15 |
| 运行时 | 脚本自解析 JAVA_HOME（不依赖外部环境） | PASS | 0.01 | 调用前外部 JAVA_HOME=E:\Tools\jdk17；解析结果=E:\Tools\jdk17 |
| 运行时 | 用户级 JAVA_HOME 已持久化 | PASS | 0.01 | E:\Tools\jdk17 |
| 运行时 | 用户级 PATH 含 JDK bin | PASS | 0.02 | E:\Tools\jdk17\bin |
| 构建工具 | gradlew / wrapper / 工程文件齐全 | PASS | 0.02 | 6 个关键文件齐全 |
| 构建工具 | Gradle wrapper 8.14.5 dist 已缓存 | PASS | 0.02 | C:\Users\26217\.gradle\wrapper\dists\gradle-8.14.5-bin\cy8remddcnhnp0z09119linzf\gradle-8.14.5\bin\gradle.bat |
| 构建工具 | gradlew -version（Gradle 8.14.5 + JVM 17） | PASS | 0.6 | Gradle 8.14.5 / JVM 17.0.15 |
| 构建工具 | Forge / ForgeGradle 缓存齐全 | PASS | 0.14 | assets 792.7 MB + mcp_repo / minecraft_repo / minecraft_user_repo 均存在 |
| 工程依赖 | gradle.properties 基准值与规划一致 | PASS | 0.01 | 10 个基准键一致：minecraft=1.20.1 / devForge=47.4.23 / minForge=47.3.0 / embeddium=0.3.31 / modernfix=5.27.89 / minModernfix=5.18.0 |
| 工程依赖 | 源 mods.toml 四依赖 + loaderVersion=[47,) | PASS | 0.01 | loaderVersion=[47,)；4 项依赖齐全；modernfix 下限引用 minModernfixVersion（D-MF-1） |
| 工程依赖 | run\mods dev 依赖 embeddium（named 变体） | PASS | 0.02 | embeddium-0.3.31_mapped_official_1.20.1-dev.jar（named 变体，1.27 MB） |
| 工程依赖 | run\mods dev 依赖 modernfix（named 变体） | PASS | 0.01 | modernfix-5.27.89_mapped_official_1.20.1-dev.jar（named 变体，0.97 MB） |
| 工程依赖 | run\mods-original 生产 jar 备份 | PASS | 0.01 | embeddium-0.3.31+mc1.20.1.jar, [现代化修复] modernfix-forge-5.27.89+mc1.20.1.jar |
| 构建工具 | scripts 目录脚本齐全（6 个） | PASS | 0.01 | 6 个脚本齐全（含 prepare-dev-deps.ps1） |
| 工程依赖 | 构建产物 build\libs jar | PASS | 0.01 | E:\捕获接口\build\libs\captureapi-0.5.0.jar（46692 bytes，2026-10-31 21:07:52） |
| 工程依赖 | Mixin/AT/refmap 基建产物（I1.1.1） | PASS | 0.67 | mixins.json / refmap.json / accesstransformer.cfg / MixinConfigs 均齐备 |
| 工程依赖 | dev 客户端 Mixin 自检日志（I1.1.1） | PASS | 0.01 | Mixin 注入自检 + 客户端 setup 基线日志均存在 |
| 工程依赖 | 配置骨架与快照日志（T1.5，dev） | PASS | 0.02 | 四 section 配置骨架存在；Loading 快照建立日志存在 |
| 工程依赖 | M1 核心自检日志（T1.2/T1.3，dev） | PASS | 0 | GL 守卫正/负例 + 最新值通道 + 资源管理器生命周期自检 PASS 日志均存在 |
| 工程依赖 | dev 客户端日志（runClient 已实测） | PASS | 0.01 | latest.log 15.3 KB，含主菜单标记 Sound engine started |
| 工程依赖 | run\crash-reports 无残留 | PASS | 0.01 | 无 crash-reports 目录（dev 客户端无崩溃记录） |
| 工程依赖 | git 工作区状态（按计划不提交） | PASS | 0.18 | 工作区 18 个未提交修改（未提交，留待审查）；origin=https://github.com/linjiacheng222/real-time-capture.git |
| 工具链 | git 可用 | PASS | 0.08 | git version 2.56.0.windows.3（C:\Users\26217\AppData\Local\Microsoft\WindowsApps\git.cmd） |
| 工具链 | ffmpeg 7.1 + libx264/AAC 编码器 | PASS | 0.15 | ffmpeg version 7.1-essentials_build-www.gyan.dev Copyright (c) 2000-2024 the FFmpeg developers；libx264=YES aac=YES |
| 工具链 | Python 3.12 可用 | PASS | 0.03 | Python 3.12.9（C:\Users\26217\AppData\Local\Programs\Python\Python312\python.exe） |
| 网络 | git ls-remote origin | PASS | 3.92 | origin 可达（第 1 次尝试成功），返回 2 行，HEAD=5aef49fd6452 |
| 网络 | PyPI 可达（Invoke-WebRequest pypi.org/simple/） | PASS | 2.61 | HTTP 200 |
| 网络 | Maven Forge 443 可达 | PASS | 0.27 | maven.minecraftforge.net:443 TCP 可达 |
| 网络 | Maven Central 443 可达 | PASS | 0.19 | repo.maven.apache.org:443 TCP 可达 |
| 网络 | Gradle Plugins 443 可达 | PASS | 0.19 | plugins.gradle.org:443 TCP 可达 |
| 网络 | 华为云镜像 443 可达 | PASS | 0.07 | mirrors.huaweicloud.com:443 TCP 可达 |
| 网络 | GitHub 443 可达 | PASS | 0.11 | github.com:443 TCP 可达 |
| 网络 | codeload.github.com 443 可达 | PASS | 0.64 | codeload.github.com:443 TCP 可达 |
| 网络 | Minecraft 资源站可达（runClient 首跑资源） | PASS | 0.09 | resources.download.minecraft.net:443 TCP 可达 |
| 硬件环境 | E 盘剩余空间 > 10GB | PASS | 0.11 | 81.4 GB 可用 / 176.1 GB |
| 硬件环境 | 端口 25580 空闲（mod 默认端口） | PASS | 1.5 | 未被监听（可安全绑定） |
| 硬件环境 | GPU: NVIDIA GeForce RTX 4050 Laptop GPU | INFO | 0 | 驱动 32.0.15.9282，日期 06/03/2026 08:00:00 |
| 硬件环境 | GPU: Intel(R) Iris(R) Xe Graphics | INFO | 0 | 驱动 31.0.101.4502，日期 06/15/2023 08:00:00 |
| 硬件环境 | 内存 | INFO | 0 | 15.6 GB |
| 硬件环境 | CPU | INFO | 0 | 13th Gen Intel(R) Core(TM) i7-13700H，14 核 / 20 线程 |
| 硬件环境 | E 盘 | INFO | 0 | 81.4 GB 可用 / 176.1 GB |

总计: PASS=39 FAIL=0 WARN=0 SKIP=0 INFO=5；结论: PASS
