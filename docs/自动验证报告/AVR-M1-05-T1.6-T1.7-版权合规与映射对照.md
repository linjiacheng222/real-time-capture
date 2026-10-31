# AVR-M1-05 T1.6+T1.7 版权合规与映射对照

> 报告类型：自动验证报告
> 关联任务：M1 / T1.6（I1.6.1~I1.6.3）、T1.7（I1.7.1~I1.7.3）
> 生成日期：2026-10-31
> 执行方：AI 执行端
> 验证环境：本地参考 jar/源码仓库 + 网络（Yarn/intermediary/Mojang mappings 下载）
> 结论：**PASS**
> 证据目录：`docs/evidence/T1.6-T1.7/`

## 1. 目的与范围

验证红线 5 的合规资产（THIRD-PARTY 实物核验、分析工作流、来源标注规范）与 T1.7 映射对照表（全链 Yarn→intermediary→official→Mojmap + Forge 事件类）。覆盖 I1.6.1~I1.7.3。

## 2. 前置条件与环境

- 分支 `feat/T1.6-T1.7` → main（提交 `592628a`，merge `35a2332`；`1f68d4e` 清理 pycache）。
- 参考资产：`参考内容/` 7 个 jar + `源码仓库/` 9 个项目（Pixelshot/DebugBridge/Record-able/PuzzlesLib/Mineshot/Embeddium/ModernFix/Oculus/Rubidium）。
- 映射数据：Yarn 1.20.1+build.10、intermediary 1.20.1、Mojang official mappings（首次运行下载，缓存 `build/mappings-cache/`，不入库）。
- Forge 类存在性：`forge-1.20.1-47.4.23_mapped_official_1.20.1-recomp.jar`。

## 3. 验证过程（步骤、命令、关键输出）

1. **许可实物核验**：解包/读取参考 jar 与源码仓库 LICENSE/元数据：
   - DebugBridge：`LICENSE` MIT（`Weikeng Chen`），与工程 `MIT-DebugBridge.txt` 逐字一致；
   - Record-able：jar `LICENSE_record-able` MIT（`Record-able contributors`），上游仓库 LICENSE MIT（`Minewind's Jo Eusebe`）→ 双版本留档；
   - Pixelshot：jar `META-INF/mods.toml` → `license="MPL-2.0"`、`authors="BarracudaATA, Fuzs, Pascal Roeleven"`、description 声明延续 Mineshot；
   - Mineshot Revived：仓库 LICENSE=GPL-3.0，仅设计参考；
   - PuzzlesLib：`LICENSE.md` MPL-2.0、`LICENSE-ASSETS.md` 资产 ARR。
2. **文档产出**：`licenses/THIRD-PARTY-NOTICES.md` 增补三节发布物声明+实物核验表；`docs/反编译分析工作流.md`；`docs/移植代码来源标注规范.md`；工程外 `分析笔记/` 5 份。
3. **映射全链校验**：
   - 命令：`python scripts\verify-mappings.py --forge-jar <forge recomp jar> --evidence docs\evidence\T1.6-T1.7\verify-mappings.txt`
   - 过程：解析 Yarn tiny v2（named→intermediary）→ intermediary tiny（official→intermediary 取逆）→ Mojang client.txt（official→Mojmap），对每行 class 数据做全链比对；对 Forge 事件行校验 recomp jar 类存在性。
   - 输出：**PASS=12 FAIL=0**。
4. **关键映射确认**：`MatrixStack→com.mojang.blaze3d.vertex.PoseStack`；`Vec3d→net.minecraft.world.phys.Vec3`；`Identifier→ResourceLocation`；`Camera→Camera`；`SoundEngine→com.mojang.blaze3d.audio.Library`；`GameRenderer→GameRenderer`；`WorldRenderer→LevelRenderer`；`MinecraftClient→Minecraft`；Forge 事件类 4/4 存在。
5. **规划文档自检**：`fill_check.py --all` → 185 I、严格填充 133、告警 0。

## 4. 结果与证据

| # | 检查项 | 期望 | 实测 | 结论 | 证据 |
|---|---|---|---|---|---|
| 1 | DebugBridge 许可 | MIT 逐字 | 一致（1069 字符） | PASS | `license-verification.txt` |
| 2 | Record-able 许可 | 双版权版本留档 | jar + 仓库两版本 | PASS | `licenses/MIT-Record-able*.txt` |
| 3 | Pixelshot 许可/署名 | 实物核验 | MPL-2.0 + 三作者 + Mineshot 归属 | PASS | `license-verification.txt` |
| 4 | Mineshot 许可 | GPL 禁复制 | GPL-3.0；仅设计参考 | PASS | `license-verification.txt` |
| 5 | THIRD-PARTY 三节结构 | 三节+修改声明+非前置 | 满足 | PASS | `licenses/THIRD-PARTY-NOTICES.md` |
| 6 | 分析工作流/5 笔记 | 文档+5 对象 | 5 份笔记存在 | PASS | `分析笔记清单.txt` |
| 7 | 标注规范 | 存在且含模板 | 文档存在 | PASS | `docs/移植代码来源标注规范.md` |
| 8 | 映射全链 | 0 FAIL | PASS=12/FAIL=0 | PASS | `verify-mappings.txt` |
| 9 | 对照表结构 | 字段+状态机+两层 | v1 完成 | PASS | `docs/附录A-映射对照表.md` |
| 10 | 校验流程 | 三步流程文档 | 文档存在 | PASS | `docs/映射校验流程.md` |
| 11 | 无臆造类名 | 第二层均来自实物 | 9 条均源自笔记 | PASS | 附录A §四 |
| 12 | 规划自检 | 0 告警 | 0 告警 | PASS | fill_check |

## 5. 判定标准与结论

- 全部自动项 PASS；映射全链 12/12；版权实物核验无冲突（规划原「三模组均 MIT」假设已按实物纠正）。
- 结论：**PASS**。M2/M3 的移植标注抽检与运行冒烟为后续项目（N1~N3）。

## 6. 人工反馈区

不适用（自动验证）。后续人工/触发项：M2 首移植 PR 标注抽检、W-023/W-024 运行冒烟。

## 7. 修复尝试记录（追加区）

- 尝试 1：tiny v2 namespace 顺序假设错误（实际 yarn 为 intermediary,named）→ 改为按 namespace 名建映射；class 行恢复解析。
- 尝试 2：Mojmap 期望名沿用规划猜测（如 `net.minecraft.client.renderer.PoseStack`）与全链结果不符 → 以全链实测为准修正数据（PoseStack 在 `com.mojang.blaze3d.vertex`；SoundEngine→`Library`）。
- 尝试 3：Forge 类存在性检查误用 `.class` 后缀 → 修正后 4/4 PASS。
- 清理：运行脚本产生 `scripts/__pycache__` → 从 git 移除并加入 `.gitignore`（提交 `1f68d4e`）。

## 8. 附件与证据索引

- 验收记录：`docs/T1.6-T1.7-验收记录.md`；红线：`docs/红线核验记录.md` §红线 5
- 证据：`docs/evidence/T1.6-T1.7/`（verify-mappings.txt、license-verification.txt、mapping-table-data.json、分析笔记清单.txt、附录A 副本）
- 规范/数据：`docs/附录A-映射对照表.md`、`docs/mapping-table-data.json`、`scripts/verify-mappings.py`、`licenses/THIRD-PARTY-NOTICES.md`
