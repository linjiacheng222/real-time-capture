# 技术评估：是否引入铁氧体磁芯（FerriteCore）

> 评估日期：2026-10-31　评估人：AI 执行端（DeepSeek Harness Lead）
> 关联：M1 硬依赖策略（D1：Embeddium / ModernFix 由用户自行安装）、Future M2 性能/内存验收
> 结论先行：**不建议把 FerriteCore 写成硬依赖（`mods.toml` mandatory）**；建议采用 **「可选兼容性能模组」** 处理——默认不随本模组声明依赖，用户按需自行安装；若你只想消除提示，也可在 ModernFix 配置中关闭「缺失性能模组警告」。详见 §4 方案对比与 §6 裁决项。

---

## 1. 你看到的提示是什么

你打开世界时看到的提示来自 **「现代化修复」（ModernFix）**，不是本模组（captureapi）发出的，也不是错误/崩溃：

- ModernFix 5.27.89 的本地 jar 语言文件里存在明确文案：
  - `modernfix.no_ferritecore` = 「**未安装铁氧体磁芯。内存占用将会非常高。**」（zh_cn）
  - `modernfix.perf_mod_warning` = 「推荐安装这些模组，但你也可以在现代化修复的配置中禁用此警告。」（zh_cn）
- ModernFix 的配置项 `mixin.feature.warn_missing_perf_mods` **默认 true**，含义是「若未安装被认为必要且高度兼容的性能模组，则在启动时显示警告」。你看到的提示即此警告（其文案描述为启动时显示，实际 toast 可能延迟到加载/进入世界时出现，不影响游戏运行，可安全忽略）。
- 禁用方法（ModernFix 自身文案给出的官方途径）：在 ModernFix 配置文件 `modernfix-mixins.properties` 末尾加入
  `mixin.feature.warn_missing_perf_mods=false`（重启游戏生效），或在 ModernFix 配置界面关闭该选项。
  - 代价：这是**总开关**，会一并关闭 ModernFix 对其他缺失性能模组的提示。

> 本地证据：`E:\捕获接口\run\mods-original\[现代化修复] modernfix-forge-5.27.89+mc1.20.1.jar` 的
> `assets/modernfix/lang/zh_cn.json`；开发环境配置文件 `E:\捕获接口\run\config\modernfix-mixins.properties` 中可见该选项默认值。

**另外**：ModernFix 本身对 FerriteCore 有专门适配——其 `mixin.perf.state_definition_construct` 选项「**只在安装 FerriteCore 时启用**」，
且 `mojang_registry_size/StateHolderMixin` 带有 `!ferritecore` 条件（检测到 FerriteCore 时回避相应改动）。这说明两者是**被设计为可共存**的，
而不是互斥冲突关系。

---

## 2. FerriteCore 是什么（事实核验）

| 项 | 事实 |
|---|---|
| 名称/作者 | FerriteCore（铁氧体磁芯），malte0811 |
| 作用 | 减少 Minecraft 内存占用（BlockState/模型/multipart/线程检测等方向的优化，客户端+服务端均有效） |
| 1.20.1 Forge 版本 | **6.0.1 (Forge)**（2023-10-22 发布；`mods.toml`：modId=`ferritecore`、`loaderVersion="[28,)"`、forge 依赖 `[46.0.1,)`、minecraft 依赖 `[1.20,1.21)`） |
| 文件 | `ferritecore-6.0.1-forge.jar`，大小 **123,034** 字节 |
| 校验 | SHA1 `417fb6ce8f52abf40bd9d0390371790f9576f8ba`；SHA512 `a1960a7c03dc32d4ccaccaf28afdd9b078758bbd62d15a91d4039a83fa9397a098e89b69591f6bd5190254d9ee97e502504154b9aec764adb8c65f000b75ba2c` |
| 许可 | **MIT**（Modrinth 项目 license=MITT；jar `mods.toml` `license="MIT"`） |
| 获取 | Modrinth：`https://modrinth.com/mod/ferrite-core/version/DG5Fn9Sz`；CDN 直链见 §7 |
| 参考保存 | 已下载到 `E:\捕获接口\run\analysis-kits\ferritecore-6.0.1-forge.jar`（E 盘，未加入工程依赖，供你决定后直接取用） |
| 兼容性 | 作者 FAQ：「Most likely yes（与大多数模组兼容），唯一例外是 Hydrogen（且 1.18+ 的 Hydrogen 已归档）」；ModernFix 官方「超低内存」指南明确建议 **FerriteCore + Sodium + ModernFix** 一起安装 |

**收益量级**：FerriteCore 作者给出的参考（All Of Fabric 3 整合包）堆内存 1792 MB → 984 MB；实际收益取决于模组数量/方块状态规模，
纯小型客户端整合包收益小于大型整合包。它不是「性能/FPS 模组」，主要是**内存占用**优化。

**风险提示（需实测）**：社区曾报告 1.19.2 下 FerriteCore + ModernFix 组合的崩溃 issue（[ModernFix #513](https://github.com/embeddedt/ModernFix/issues/513)，标题为 1.19.2 场景）；1.20.1 + 当前 ModernFix 5.27.89 未必复现，但若安装，建议做一次共存冒烟（见 §5.4），并保留回退基线。

---

## 3. 与「捕获接口」项目的相关性分析

### 3.1 功能相关性：**没有依赖关系**

- 本模组的硬依赖只有 Embeddium（渲染）与 ModernFix（区块装载优化），均按 D1 由用户自行安装，本模组只做 `mandatory` 声明；
- FerriteCore 不提供本模组需要的任何 API/事件/Mixin 锚点；本模组的采集链路（M2 的 FBO/PBO/离屏渲染、M3 音频环回、M4 网络输出）**不经过**方块状态/模型内存结构；
- 因此 FerriteCore 对本模组而言是 **可选性能优化**，不是功能前置。

### 3.2 对红线与兼容性的影响：低风险、可控

| 维度 | 判断 |
|---|---|
| 红线 2（Mojmap/禁 Connector） | FerriteCore 是 Forge 模组，非 Fabric/Quilt 混合加载器；不改变本工程映射/加载方式 |
| 红线 3（GL 线程/资源生命周期） | FerriteCore 的 Mixin 目标是方块状态/模型/线程检测，与本模组规划的渲染入口（`RenderLevelStageEvent`/FBO 封装）不同层；无直接重叠 |
| 红线 5（版权） | FerriteCore 为 MIT；本项目**不复制其代码、不打包分发**（仅用户自行安装），因此不新增代码义务；若未来分发整合包需按 MIT 保留声明 |
| ModernFix 兼容 | ModernFix 对 FerriteCore 有条件适配（§1）；两者官方指南建议同装 |
| Embeddium 兼容 | 渲染层 vs 内存结构层，正交；Emb 用户中同时装 FerriteCore 是常见组合 |

### 3.3 成本侧：硬依赖 vs 可选的差别很大

- **硬依赖**意味着：所有用户必须安装该 jar 才能进游戏；发布/兼容矩阵多一个强制变量；我们的 `mods.toml`、安装文档、故障排查、版本下限都要维护；而收益并非功能必需。
- **可选**意味着：代码零改动、依赖契约不变；只需在文档/验收矩阵中登记「可选组合」，愿意装的人自行安装。

---

## 4. 方案对比与推荐

| 方案 | 是否改工程依赖 | 用户成本 | 预期收益 | 主要风险 | 评价 |
|---|---|---|---|---|---|
| **A. 不装/忽略提示** | 否 | 0 | 无内存收益；提示仍在（可关） | 无 | 可接受，最简单 |
| **B. 可选安装（推荐）** | 否（不写 `mods.toml`） | 用户按需装 1 个 jar | 可能降低客户端内存；消除 ModernFix 提示 | 需一次共存冒烟；新增一个文档/测试矩阵状态 | **最优平衡** |
| **C. 写成硬依赖** | 是（mandatory） | 所有用户必须装 | 同上 | 违背 D1 依赖最小化；维护/兼容/用户门槛上升；无功能必要性 | **不推荐** |
| **D. 代码内检测并提示** | 是（自写提示） | 无安装 | 无收益 | 重复提示、噪音、维护成本 | 不必要（ModernFix 已提示） |

**我的意见（明确）**：

1. **不把 FerriteCore 加入本项目的硬依赖**（不改 `mods.toml` mandatory、不加入 dev 硬依赖基线）；
2. 建议 **方案 B**：把它登记为「可选性能模组」，在 README/兼容性说明中给出推荐版本 `6.0.1 (Forge)`、校验值与安装/回退方法；若你愿意，我们可在 M2 的性能/内存验收矩阵中增加 «+FerriteCore» 一态（非默认验收项）；
3. 如果你只是**不想看到提示**：二选一即可——安装 FerriteCore（推荐，顺带收益），或在 ModernFix 配置中关闭 `mixin.feature.warn_missing_perf_mods`（无安装成本，但会隐藏其他缺失性能模组警告）；
4. 如果你坚持「所有用户都应有同样内存表现」→ 才考虑方案 C；但按当前 D1（硬依赖仅 Emb/MF）与项目定位，我认为**不建议**。

---

## 5. 如果决定安装：处理清单

### 5.1 版本与来源

- 版本：**FerriteCore 6.0.1 (Forge)**（1.20.1；与 Forge 47.4.23 兼容；`mods.toml` 允许 1.20~1.20.x）；
- 文件：`ferritecore-6.0.1-forge.jar`（123,034 字节）；
- 校验（下载后核对）：SHA1 `417fb6ce8f52abf40bd9d0390371790f9576f8ba`，SHA512 见 §2；
- 已保存副本：`E:\捕获接口\run\analysis-kits\ferritecore-6.0.1-forge.jar`（E 盘；未进 git、未打进本模组）。

### 5.2 安装位置

- **客户端必装**（捕获模组在客户端）；单人/客户端采集场景服务端不装也可工作；
- 如果该客户端也连接多人服务器：FerriteCore 官方 FAQ 建议「两侧都装」才能拿到服务端侧的内存收益，但这与本模组功能无关；
- PCL 实例：把 jar 放入该实例的 `mods` 目录（与 Embeddium/ModernFix/captureapi 同目录）；本工程 dev 的 `run/mods` 属 Mojmap named 环境，**不要直接放生产 jar**（可能触发与 Embeddium/ModernFix 同类的 `@Shadow` SRG 名问题），dev 冒烟优先在 PCL 实例做。

### 5.3 配置与冲突处理

- **装 FerriteCore 后不需要改 ModernFix 配置**：ModernFix 会自动启用其 `state_definition_construct` 适配、并回避与 FerriteCore 重叠的改动；
- 若出现启动/进世界崩溃：先移除 FerriteCore 回到原基线（Embeddium+ModernFix+captureapi），把日志 `latest.log`/`crash-reports` 留给我定位；不要同时改动其他性能模组；
- 若不安装、只想关提示：在 **该实例** 的 `config/modernfix-mixins.properties` 末尾加 `mixin.feature.warn_missing_perf_mods=false`（重启生效）。

### 5.4 共存验证清单（安装后执行；建议由你在 PCL 实例完成，我按结果记录）

| # | 检查 | 期望 |
|---|---|---|
| 1 | 启动游戏 | 无崩溃；Mods 列表出现 FerriteCore 6.0.1 |
| 2 | 打开世界/进入存档 | 正常进入；不再出现 ModernFix 的「未安装铁氧体磁芯」提示 |
| 3 | 日志 | 无 `Mixin apply failed`/`InvalidMixinException`/`ClassCastException` 等；`latest.log` 可保存 |
| 4 | 本模组 | 日志仍含 `[CaptureAPI] 主类构造完成`/`底座就绪`；无新增异常 |
| 5 | 基线对比 | 记录安装前后 F3 内存占用/帧率（同世界、同视角、同时间），作为后续 M2 性能基线附件 |
| 6 | 回退 | 移除 jar 后可恢复正常启动（本模组不依赖它，回退无副作用） |

### 5.5 工程侧工作量（若你选方案 B）

- 无需改代码/`mods.toml`；
- 文档：README「可选性能模组」段 + `docs/技术评估-FerriteCore引入论证.md` 保留；
- 验收：M2 内存/性能矩阵增加可选一态 `+FerriteCore`（不纳入默认门槛）；
- dev：默认不加入 `run/mods`；如需 dev 世界测试，再评估 `prepare-dev-deps.ps1` 为其生成 named 变体（额外维护成本，非必要不做）。

---

## 6. 结论与待你裁决

**我的意见**：FerriteCore 对本项目是「非必需、低风险、有内存收益」的可选优化。**不要写成硬依赖**；建议按方案 B 处理，
默认不装、文档登记为可选；若你在意启动提示或内存，安装 `6.0.1 (Forge)` 并做 §5.4 一次共存冒烟；否则用 ModernFix 配置关闭该警告即可。

**请你裁决（三选一）**：

- **A**：不安装、忽略提示（或我在文档写明「如何关闭 ModernFix 该警告」）；
- **B（推荐）**：作为可选性能模组处理——我更新 README/兼容性说明与验收矩阵（代码不动），你在 PCL 实例按 §5 安装并反馈 §5.4 结果；
- **C**：改为硬依赖（需要你明确覆盖 D1 决策；我会改 `mods.toml`、版本区间、安装文档并扩测试矩阵）。

在收到你的选择前，我不会改动任何依赖/工程配置。

---

## 7. 证据与引用

**本地实物证据**
- ModernFix 5.27.89 语言文件（`modernfix.no_ferritecore`、`modernfix.perf_mod_warning`、`modernfix.option.mixin.feature.warn_missing_perf_mods`）：
  `E:\捕获接口\run\mods-original\[现代化修复] modernfix-forge-5.27.89+mc1.20.1.jar`
- ModernFix 配置默认项：`E:\捕获接口\run\config\modernfix-mixins.properties`（`mixin.feature.warn_missing_perf_mods=true`）
- FerriteCore 6.0.1 jar 及其 `mods.toml`/MANIFEST：`E:\捕获接口\run\analysis-kits\ferritecore-6.0.1-forge.jar`（SHA1/SHA512 已在 §2 核对）

**外部资料**
- FerriteCore 项目页（Modrinth，MIT、1.20.1 支持、FAQ）：<https://modrinth.com/mod/ferrite-core>
- FerriteCore 6.0.1 (Forge) 版本页（下载/哈希）：<https://modrinth.com/mod/ferrite-core/version/DG5Fn9Sz>
- FerriteCore 源码（MIT）：<https://github.com/malte0811/FerriteCore>
- ModernFix「Ultra-Low Memory Footprint」wiki（明确建议 FerriteCore + Sodium + ModernFix）：
  <https://github.com/embeddedt/ModernFix/wiki/Ultra%E2%80%90Low-Memory-Footprint>
- ModernFix issue #513（1.19.2 下 FerriteCore+ModernFix 崩溃历史记录，作为需冒烟的参考）：
  <https://github.com/embeddedt/ModernFix/issues/513>
