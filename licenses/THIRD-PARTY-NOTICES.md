# THIRD-PARTY-NOTICES（第三方开源协议声明）

本模组「捕获接口（Capture Interface）」以 **MIT License** 发布（见本项目 `LICENSE`）。

本模组开发过程中参考并按规则复用了下列开源项目。各项目协议**原文**完整保存在本目录 `licenses/` 下，随源码与发布物一同分发。

## 参考项目与协议总表

| 项目 | 作者 | 源码仓库 | 协议（已实物核验 2026-10-24） | 在本项目中的角色 | 复用方式 |
|---|---|---|---|---|---|
| Record-able | JoEusebe 等（Capable Creations Group） | github.com/JoEusebe/record-able | **MIT**（`MIT-Record-able.txt`） | M3 音频采集移植基准（OpenAL 环回、环形缓冲、监听器同步、时间戳） | 代码可直接复用，保留原版权声明 |
| DebugBridge | use-ai-for-mc | github.com/use-ai-for-mc/debugbridge | **MIT**（`MIT-DebugBridge.txt`） | M4 网络与性能移植基准（PBO 双缓冲、JPEG、帧缓存、HTTP/WS） | 代码可直接复用，保留原版权声明 |
| Pixelshot | Fuzs（Fuzss）等 | github.com/Fuzss/pixelshot（1.20.1 分支） | **MPL-2.0**（仓库无 LICENSE 文件，以 gradle.properties `modLicense=MPL-2.0` 及 Modrinth/CurseForge 标注为准；原文见 `MPL-2.0.txt`） | M2 视频流主移植基准（离屏渲染、相机矩阵、状态保护） | 代码可复用：被复用文件保持 MPL-2.0 并在文件头标注来源；可与本项目其余 MIT 文件混合发布 |
| PuzzlesLib | Fuzs（Fuzss） | github.com/Fuzss/puzzleslib（1.20.1 分支） | **代码 MPL-2.0**（`MPL-2.0.txt`）；**资产 All Rights Reserved**（`LICENSE-ASSETS.md`） | Pixelshot 前置库（本项目不依赖，仅了解其结构） | 代码复用规则同上；**资产（图标/贴图等）禁止复用** |
| Mineshot Revived | pascallj / chixvv1 | github.com/chixvv1/mineshot-revived-fabric | **GPL-3.0**（`GPL-3.0.txt`） | 设计参考（Pixelshot 的思路前身） | **禁止复制任何代码**：GPL-3.0 具强传染性，复制会把整个模组拖入 GPL，与本项目 MIT 声明直接冲突；仅参考公开的设计思路与行为描述 |
| Embeddium | embeddedt | github.com/embeddedt/embeddium（20.1/forge 分支） | **GPL-3.0 / LGPL-3.0 双许可**（COPYING / COPYING.LESSER，原文见 `GPL-3.0.txt` / `LGPL-3.0.txt`） | 硬依赖（渲染主力，用户自行安装） | 仅作运行时依赖声明（mandatory），不复制、不修改其代码 |
| ModernFix | embeddedt | github.com/embeddedt/ModernFix（1.20 分支） | **LGPL-3.0**（`LGPL-3.0.txt`） | 硬依赖（区块加载优化，用户自行安装） | 仅作运行时依赖声明（mandatory），不复制代码 |
| Oculus | Asek3 | github.com/Asek3/Oculus（1.20.1-new 分支） | **LGPL-3.0**（README 声明；原文见 `LGPL-3.0.txt`） | 光影兼容适配对象（非依赖） | 不复制代码 |
| Rubidium | Asek3 | github.com/Asek3/Rubidium（1.20/dev 分支） | **LGPL-3.0**（`LGPL-3.0.txt`） | 参考（与 Embeddium 互斥，不可同装） | 不复制代码 |

## 复用规则摘要（总监 2026-10-24 授权"代码可以视情况复用"）

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
