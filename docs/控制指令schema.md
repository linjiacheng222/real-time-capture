# WebSocket 控制指令 Schema（T4.3，契约冻结候选）

> 2026-11-02 修订提示：当前已有 34 个 action（含 capture/recording/config/filter/overlay 等），本文仅覆盖相机域；完整列表以 `docs/API使用说明.md` 为准。

> 状态：**T4.3 代码完成**（2026-11-01）；dev 运行时已实测 create/list/status/delete 与三类错误路径；运镜延迟/视频实推待测试分计划。
> 关联：I4.3.1~I4.3.4、CameraManager（I2.1）、错误码表。

## 一、连接

`ws://127.0.0.1:25581/`（token 开启时 `?token=...`）。请求/响应均为 JSON 文本消息；二进制消息为音视频流（T4.2）。

## 二、请求信封

```json
{"id":"<客户端配对id>","action":"camera.create","params":{...}}
```

- `id`：客户端生成，响应原样回带；缺省空字符串；
- `action`：白名单指令名；
- `params`：参数对象（无参数可省略）。

## 三、指令集（相机域）

| action | 参数 | 说明 |
|---|---|---|
| `camera.create` | `x,y,z`（double，默认 0）、`yaw,pitch,roll`（度，默认 0；pitch∈[-90,90]）、`fov`（默认 70，范围 (1,179)）、`width,height`（省略=全局分辨率；必须同时给出且为正）、`shape`/`projectionId`（默认 `rect`）、`boundEntity`（UUID 字符串）、`offsetX/Y/Z` | 创建并返回 CameraSpec；首个相机自动 active |
| `camera.adjust` | `id`（必填）+ 同上全量参数 | 整体替换参数，保留 id/暂停态 |
| `camera.activate` | `id` | 切换单路 active |
| `camera.pause` / `camera.resume` | `id` | 暂停/恢复 |
| `camera.delete` | `id` | 删除；删除 active 后 active=0 |
| `camera.list` | 无 | `{active, cameras:[...]}` |
| `camera.status` | 无 | `{active, count, camera?}` |

返回的相机对象字段：`id,x,y,z,yaw,pitch,roll,fov,width,height,paused,shape[,boundEntity,offsetX/Y/Z]`。

> `fps_max` 等全局参数写入属 M6 双入口治理范围，T4.3 暂未开放指令（按“问题先保留”记录）。

## 四、响应

成功：
```json
{"id":"c1","ok":true,"result":{...}}
```
失败：
```json
{"id":"c1","ok":false,"error":{"code":"E_PARAM_RANGE","message":"fov 必须在 (1,179)"}}
```

## 五、错误码（只增不改语义）

| code | 触发 |
|---|---|
| `E_INVALID_JSON` | JSON 解析失败（当前统一走 E_INTERNAL 兜底，后续细分） |
| `E_UNKNOWN_ACTION` | action 不在白名单 |
| `E_MISSING_PARAM` | 必填参数缺失（如 id/action） |
| `E_PARAM_RANGE` | 类型/范围/格式非法（fov、pitch、UUID、width/height 等） |
| `E_NOT_FOUND` | 相机 id 不存在 |
| `E_INTERNAL` | 未预期异常（内部堆栈只写日志） |
| `E_RATE_LIMIT`/`E_NO_PERMISSION` | 预留：SP/LAN 恒通过，专用服权限归 M9 |

## 六、dev 运行时证据（2026-11-01）

```
CREATE -> {"id":"c1","ok":true,"result":{"id":1,"x":10.5,...,"width":1280,"height":1024,"shape":"rect"}}
LIST   -> {"id":"l1","ok":true,"result":{"active":1,"cameras":[...]}}
STATUS -> {"id":"s1","ok":true,"result":{"active":1,"count":1,"camera":{...}}}
BADACT -> E_UNKNOWN_ACTION
MISSING-> E_MISSING_PARAM（缺 id）
RANGE  -> E_PARAM_RANGE（fov=500）
DELETE -> {"id":"d1","ok":true,"result":{"deleted":1,"active":0}}
LIST2  -> {"active":0,"cameras":[]}
```

## 七、已知缺口

- 视频实推需 active 相机 + 进入世界（AFTER_LEVEL 才触发离屏渲染），本测试只在主菜单验证指令；世界内视频实推/运镜延迟/画面正确性待测试分计划；
- Python `websocket-client` 示例脚本、并发 100 指令 id 配对压测、权限路径、空/错误 JSON 细分未执行；
- fps_max 指令（M6 双入口）未开放。
