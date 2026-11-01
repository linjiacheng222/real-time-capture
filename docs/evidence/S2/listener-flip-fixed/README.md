# S2.4 监听器修复验证（2026-11-01 21:28）

> **修复后左右声道翻转 PASS**：相机 yaw0→yaw180 后，固定 +X 声源的左右声道优势翻转：
> - yaw0-sound：L=870.72 / R=701.25（L 高，+X 为相机左）
> - yaw180-sound：L=147.50 / R=575.82（R 高，+X 变为相机右）

对比修复前（`../listener-flip/`）：yaw0 L479/R106 → yaw180 L482/R296，未翻转。

## 修复内容（P1.1）

- `AudioListenerSync` 不再由渲染线程直接写 AL listener（AL 上下文 current 在采集线程，跨线程写入无效）；
- 渲染侧只在 `beforeOffscreenRender` 登记最新 `CameraPose` + 时间戳；
- **音频采集线程在每次 `alcRenderSamplesSOFT` 前**调用 `AudioListenerSync.applyForCapture()`：最新相机位姿（<250ms）→ 相机 listener；否则回退玩家 listener；
- 因此相机激活时，环回混音按相机位姿；相机暂停/删除/无渲染约 250ms 后回退玩家位姿。

## 证据

`pcm-yaw0-silence/sound.raw`、`pcm-yaw180-silence/sound.raw`（44.1kHz/16bit/立体声）；脚本 `scripts/s2-listener-flip-check.ps1`。

## 注意/取舍（待用户裁决）

- 环回设备同时是**唯一扬声器输出**；相机激活时用户听到的音频也变为相机视角（不再是玩家视角）。
- 这是“相机方向性”与“玩家听觉零扰动”的物理取舍；当前选择优先保证捕获方向性（S2.4 要求），相机激活期间玩家听感改变，需 S2.1/用户确认可接受或改为双混音方案。
