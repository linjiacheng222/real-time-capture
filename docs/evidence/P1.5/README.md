# P1.5 Oculus 光影适配——用户最小测试操作单（2026-11-01）

## 当前状态
- `ShaderCompatHooks` 已探测 `oculus`/`embeddium`，dev 环境无 Oculus（`backend=none`）；
- **离屏光影适配 W-043 尚未实装**：Oculus 激活时离屏帧可能黑屏/条纹/主画面，属已知缺口；
- 本轮新增诊断字段：`/health` 的 `shader_backend`/`shader_detected`/`shader_active`，以及 WS 指令 `shader.status`。

## 用户操作单（PCL 实例，已含 Oculus）
1. 启动 PCL 实例（mods 已含 oculus 1.8.0 + Embeddium + ModernFix + captureapi 最新 jar）；
2. 进入世界；确认光影包可正常加载（不要求特定包，任选常用 shaderpack）；
3. 仓库目录运行：
   ```
   powershell -ExecutionPolicy Bypass -File scripts\s1-capture-check.ps1
   ```
   （脚本会 unpause、创建相机、拉帧；若失败也会留下证据）；
4. 再运行：
   ```
   curl http://127.0.0.1:25580/health
   ```
   记录 `shader_backend`、`shader_detected`、`shader_active` 三项；
5. 浏览器/FFmpeg 查看相机帧：是否与相机视角一致、是否黑屏/花屏/条纹/主画面；
6. 把以下信息回复开发端：
   - Oculus 版本 + shaderpack 名称/版本；
   - `/health` 三项值；
   - 帧表现（正常/黑屏/条纹/主画面/崩溃）+ 截图或日志尾部。

## 预期判据（W-043/W-044 验收）
- 光影开启/关闭两种模式下，相机帧均为正确世界画面（允许光影效果差异）；
- 切换光影包不崩溃；关闭光影后恢复。
- 当前代码预计**不能**通过；本操作单用于收集失败形态，作为 W-043 实装输入。
