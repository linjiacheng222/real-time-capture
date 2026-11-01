# S1 诊断证据（Spike B）

> 2026-11-01 21:00 修复后 S1.1/S1.2 通过，当前证据见 `../auto-pass/`。
> 本目录保留定位过程：无 Embeddium 对照、FBO 回读源错误、相机位姿应用。

## 关键结论

- 修复前“相机位姿不生效 + 垂直条纹”并非 Embeddium 专属：去掉 Embeddium/ModernFix 后同样出现；
- 根因：`LevelRenderer.renderLevel` 内部临时绑定主 framebuffer，之后 `glReadPixels` 读到主画面而非离屏 FBO；
- 修复：回读前 `fbo.bind()` 重新绑定离屏 FBO（流式/实时调取两条路径）。

## 文件

| 文件 | 说明 |
|---|---|
| `no-emb-fbo-down1.jpg` / `down2.jpg` | 无 Embeddium 对照、FBO rebind 后正确俯视地形帧（证明渲染本身正常） |
| `target-down1.jpg` / `target-down2.jpg` | 目标环境（Embeddium+ModernFix）FBO rebind 后干净帧 |
| `pos2-small.jpg` / `pos2-far.jpg` | 相机位置调整前后变化（证明位姿生效） |

历史失败证据（修复前条纹/主视图）：`../manual-check/`。
最终自动通过证据：`../auto-pass/`（脚本 exit=0，S1.1/S1.2 PASS）。
