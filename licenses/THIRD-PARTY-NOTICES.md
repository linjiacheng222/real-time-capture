# THIRD-PARTY-NOTICES（第三方开源协议声明）

本模组「捕获接口（Capture Interface）」以 **MIT License** 发布（见本项目 `LICENSE`）。

本模组开发过程中参考并按规则复用了下列开源项目。各项目协议**原文**完整保存在本目录 `licenses/` 下，随源码与发布物一同分发。

## 参考项目与协议总表

| 项目 | 作者 | 源码仓库 | 协议（已实物核验 2026-10-24） | 在本项目中的角色 | 复用方式 |
|---|---|---|---|---|---|
| Record-able | JoEusebe 等（Capable Creations Group） | github.com/JoEusebe/record-able | **MIT**（`MIT-Record-able.txt`） | M3 音频结构基准（ALC_SOFT_loopback 接管 + Java Sound 中继、监听器同步、时间戳；参考快照为 Fabric 1.20.4，1.20.1 注入层需重写） | 结构可参考/复用，保留原版权声明；不宣称原样移植 |
| DebugBridge | use-ai-for-mc | github.com/use-ai-for-mc/debugbridge | **MIT**（`MIT-DebugBridge.txt`） | M4 网络框架选型基准（JDK HttpServer、Java-WebSocket、ImageIO JPEG；无 PBO/帧缓存/广播/鉴权，后者为本项目自研增量） | 代码可直接复用，保留原版权声明 |
| Pixelshot | Fuzs（Fuzss）等 | github.com/Fuzss/pixelshot（1.20.1 分支） | **MPL-2.0**（仓库无 LICENSE 文件，以 gradle.properties `modLicense=MPL-2.0` 及 Modrinth/CurseForge 标注为准；原文见 `MPL-2.0.txt`） | 设计参考（仅一次性截图入口 `GameRenderer#renderLevel` 先例；无离屏循环/FBO 复用/相机矩阵/光影分支可复用） | 如需复用其代码：被复用文件保持 MPL-2.0 并在文件头标注来源；当前尚无代码复用 |
| PuzzlesLib | Fuzs（Fuzss） | github.com/Fuzss/puzzleslib（1.20.1 分支） | **代码 MPL-2.0**（`MPL-2.0.txt`）；**资产 All Rights Reserved**（`LICENSE-ASSETS.md`） | Pixelshot 前置库（本项目不依赖，仅了解其结构） | 代码复用规则同上；**资产（图标/贴图等）禁止复用** |
| Mineshot Revived | pascallj / chixvv1 | github.com/chixvv1/mineshot-revived-fabric | **GPL-3.0**（`GPL-3.0.txt`） | 设计参考（Pixelshot 的思路前身） | **禁止复制任何代码**：GPL-3.0 具强传染性，复制会把整个模组拖入 GPL，与本项目 MIT 声明直接冲突；仅参考公开的设计思路与行为描述 |
| Embeddium | embeddedt | github.com/embeddedt/embeddium（20.1/forge 分支） | **GPL-3.0 / LGPL-3.0 双许可**（COPYING / COPYING.LESSER，原文见 `GPL-3.0.txt` / `LGPL-3.0.txt`） | 硬依赖（渲染主力，用户自行安装） | 仅作运行时依赖声明（mandatory），不复制、不修改其代码 |
| ModernFix | embeddedt | github.com/embeddedt/ModernFix（1.20 分支） | **LGPL-3.0**（`LGPL-3.0.txt`） | 硬依赖（区块加载优化，用户自行安装） | 仅作运行时依赖声明（mandatory），不复制代码 |
| Oculus | Asek3 | github.com/Asek3/Oculus（1.20.1-new 分支） | **LGPL-3.0**（README 声明；原文见 `LGPL-3.0.txt`） | 光影兼容适配对象（非依赖） | 不复制代码 |
| Rubidium | Asek3 | github.com/Asek3/Rubidium（1.20/dev 分支） | **LGPL-3.0**（`LGPL-3.0.txt`） | 参考（与 Embeddium 互斥，不可同装） | 不复制代码 |

## 复用规则摘要（依据 2026-10-24 版权裁决）

1. **MIT 源**（Record-able、DebugBridge）：可自由复制与改写，原版权与许可声明随文件保留；
2. **MPL-2.0 源**（Pixelshot）：可复制与改写；**被复用的文件保持 MPL-2.0**（文件头加注 `// SPDX-License-Identifier: MPL-2.0` 与来源说明），并在本文件登记；MPL 为文件级弱传染，允许与其余 MIT 文件混合发布；
3. **GPL-3.0 源**（Mineshot Revived）：**绝对禁止复制代码**，仅参考设计；
4. **LGPL 依赖**（Embeddium、ModernFix）：运行时依赖关系不产生代码义务；不修改、不再分发依赖本身；
5. **资产红线**：任何参考项目的贴图、图标、logo 等资源一律不复用，本项目资产全部自绘。

## 协议原文索引（本目录）

- `LICENSE` —— 本模组 MIT
- `MPL-2.0.txt` —— Mozilla Public License 2.0 全文
- `GPL-3.0.txt` —— GNU GPL v3 全文
- `LGPL-3.0.txt` —— GNU LGPL v3 全文
- `MIT-Record-able.txt` / `MIT-DebugBridge.txt` —— 两项 MIT 协议原文
- `MIT-Record-able-repo.txt` —— Record-able 上游源码仓库 `LICENSE` 变体（版权行与 jar 内不同，见下方 §实物核验）

---

## 实物核验补充（2026-10-31，T1.6）

> 依据：红线 5「以仓库/发布物实物为准」。以下结论均来自本地 jar/仓库文件直接读取，不依赖网页或记忆。

| 项目 | 核验对象与证据 | 结论 |
|---|---|---|
| Record-able | `record-able-V1-0.11-legacy.jar` 内 `LICENSE_record-able`：`Copyright (c) 2026 Record-able contributors`（1081 字节，MIT 全文）；上游源码仓库 `record-able-main/LICENSE`：`Copyright (c) 2026 Minewind's Jo Eusebe`（1081 字节，MIT 全文） | 两处版权行不同：**发布物/引用 jar 用 `Record-able contributors`**（`MIT-Record-able.txt`），上游仓库变体存档为 `MIT-Record-able-repo.txt`；许可类型均为 MIT（W-015 销号：两版本均留存） |
| DebugBridge | 源码仓库 `debugbridge-main/LICENSE`：MIT，`Copyright (c) 2026 Weikeng Chen`；与工程 `MIT-DebugBridge.txt` 逐字一致（1069 字符，行尾差异已归一） | MIT 原文与版权行一致，保留 |
| Pixelshot | `Pixelshot-v20.1.0-1.20.1-Forge.jar` 的 `META-INF/mods.toml`：`license = "MPL-2.0"`、`authors = "BarracudaATA, Fuzs, Pascal Roeleven"`、`description = "… A continuation of Mineshot by BarracudaATA."`；源码 `pixelshot-1.20.1/gradle.properties`：`modLicense=MPL-2.0` | W-014 销号：**Pixelshot 为 MPL-2.0**（非 MIT），社区 fork 署名与上游 Mineshot 归属已在 jar 元数据中明确；如复用其文件须保持 MPL-2.0 文件级声明 |
| Mineshot Revived | 源码仓库 `mineshot-revived-fabric-master/LICENSE`：GPL-3.0 全文；`README.md` 声明「Most code based on original Mineshot by Barracuda」「earlier Forge port by pascallj」 | GPL-3.0 强传染：**仅可参考设计思路，禁止复制任何代码**；原 Mineshot/Barracuda 与 pascallj 的署名在参考记录中保留 |
| PuzzlesLib | 源码仓库 `LICENSE.md`：MPL-2.0 全文；`LICENSE-ASSETS.md`：资产 All Rights Reserved | 代码 MPL-2.0、资产禁止复用；本项目不依赖、不移植 |
| Embeddium / ModernFix | 运行依赖，非移植来源（依赖声明见 `mods.toml`；协议见 `GPL-3.0.txt` / `LGPL-3.0.txt`） | 不进移植声明节，仅作依赖合规说明 |

## 发布物三节声明（I1.6.1；随 jar/发布物分发）

> 说明：以下三节为发布物声明正文；M1 阶段实际复用状态为「尚无参考模组代码复制」（截至 T1.6 验收），
> 修改声明为模板+当前状态，M2 起每次移植 PR 按《移植代码来源标注规范》回填实际移植范围。
> 三项目均**不作为运行前置**（本模组硬依赖仅为 Embeddium/ModernFix，见 mods.toml）。

### 第一节：Mineshot Revived 与 Pixelshot 社区 Forge 版

- **原项目**：Mineshot Revived（Fabric 分支，github.com/chixvv1/mineshot-revived-fabric；源自 pascallj/mineshot-revived 与 Barracuda 的 Mineshot）；Pixelshot（github.com/Fuzss/pixelshot，1.20.1 Forge 分支）
- **协议**：Mineshot Revived 为 **GPL-3.0**（`licenses/GPL-3.0.txt`）；Pixelshot 为 **MPL-2.0**（`licenses/MPL-2.0.txt`，jar `mods.toml` 与 gradle.properties 实证）
- **署名**：Barracuda（Mineshot 原作者）、pascallj（早期 Forge 移植）、chixvv1（Fabric 分支）、Fuzs/Pascal Roeleven（Pixelshot 维护）
- **修改声明（当前）**：本项目**未复制** Mineshot Revived 任何代码（GPL 禁止）；Pixelshot 仅作一次性截图/正交相机设计参考，当前亦无文件复制。若未来复用 Pixelshot 文件，该文件保持 MPL-2.0 并在文件头标注来源（见《移植代码来源标注规范》）。
- **运行前置**：否。

### 第二节：DebugBridge

- **原项目**：DebugBridge（github.com/use-ai-for-mc/debugbridge）
- **协议**：MIT（`licenses/MIT-DebugBridge.txt`，版权行 `Copyright (c) 2026 Weikeng Chen`）
- **修改声明（当前）**：本项目尚未复制 DebugBridge 代码；M4 若复用其网络/截图实现，将在移植类头标注 `@source DebugBridge <类名>` 与 `@changes`，并在本文件汇总修改范围。
- **运行前置**：否。

### 第三节：Record-able

- **原项目**：Record-able（github.com/JoEusebe/record-able）
- **协议**：MIT（`licenses/MIT-Record-able.txt`，版权行以 jar 内 `Record-able contributors` 为准；上游仓库变体见 `MIT-Record-able-repo.txt`）
- **修改声明（当前）**：本项目尚未复制 Record-able 代码；M3 若复用其环回采集/监听器同步实现，将按规范标注来源与修改摘要。
- **运行前置**：否。
