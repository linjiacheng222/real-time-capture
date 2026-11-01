# S1 证据说明（2026-11-01）

> 结论：**S1.1 有帧但画面存在垂直条纹/花屏；S1.2 相机位置变化未反映到离屏画面 → S1 未通过**，进入 Spike B 排查。
> 主画面本身正常（`main-running.png`），说明问题在离屏渲染/回读路径而非主渲染。

| 文件 | 说明 |
|---|---|
| `main-running.png` | 世界运行中的主画面（正常，无条纹；窗口 698x422） |
| `main-restored.png` | 暂停菜单（修复前自动暂停导致离屏冻结；后加 `client.unpause`） |
| `fix1.jpg` | 修复 Camera.setup 覆盖后的离屏帧（相机 y=140,yaw=0；有垂直条纹） |
| `fix3.jpg` | 相机 yaw=90 后离屏帧（画面变化但仍条纹） |
| `unpause-yaw90.jpg` | `client.unpause` 后离屏帧（yaw=90；条纹仍在） |
| `far.jpg` | 相机移到 (1000,200,1000) 后离屏帧（画面仍为近玩家森林 → 位姿未生效/回读疑似主视图） |
| `high-alt.jpg` | 相机 y=260 后离屏帧（同上） |

首次自动脚本证据见 `../20261101-202847/`：该次 S1.1 成功取到 1280x1024 JPEG，但 S1.2 两帧相同（脚本当时未重试/暂停未处理），不能作为通过依据。

**关键日志**（`run/logs/latest.log`）：
- `[S1-DIAG] frame#=... specPos=(...)` 显示离屏管线读取到更新后的相机参数；
- `[S1-DIAG] Camera.setup override applied#=...` 显示 `Camera.setup` 后已重新应用自定义机位；
- 但帧内容仍近似玩家视角，且垂直条纹在多次相机调整后稳定存在。

**下一步（Spike B）**：
1. 验证 Embeddium 重入渲染是否导致地形走主相机/旧渲染列表：临时无 Embeddium 对比实验；
2. 检查 FBO 绑定与 PBO 回读（读回目标、`GL_PACK_*`、主 framebuffer 干扰）；
3. 尝试不同 `RenderLevelStage` 时机或主画面复制降级；
4. 通过后再执行 S1.3/S1.4 与 S2。
