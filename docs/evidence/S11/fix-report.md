# 客户端区块按需同步机制（S11）修复/实现报告

> 日期：2026-11-04 CST  
> 工程：`E:\捕获接口`  
> 计划：`docs/客户端区块同步机制落地计划.md`（CS1–CS3）  
> 代码提交：`5634cd9`（`feat:【区块同步机制】客户端按需同步相机区块`）  
> 构建：`scripts/build.ps1` **24/24 PASS**；magic audit **0**；jar `401075 B`  
> 本报告为后续 docs commit；最终报告提交 hash 以 `git log` 为准。

---

## 1. 实现结果

### CS1：同步主体

新增：

- `src/main/java/com/captureapi/video/chunk/ChunkSyncRequester.java`
  - `update(Set<Long>)`：目标集合 diff，2048 上限截断并 WARN；
  - `onClientTick/TickEvent`：每客户端 tick 最多 **8** 个新块；
  - `toSend` 经集成服 `server.execute(...)` 调
    `ChunkMap.updateChunkTracking(player,pos,new MutableObject<>(),true,false)`；
  - `toForget` 仅当目标 ∉ desired **且** `ChunkMap.isChunkInRange(playerChunkX,playerChunkZ,cx,cz,viewDistance)==false`；
    发 `ClientboundForgetLevelChunkPacket(pos.x,pos.z)`；
  - `[CHUNK/SYNC] 入队=... 已同步=... 卸载=...（desired=...,synced=...）` 日志；
  - `releaseAll()` 世界退出清空；
  - `syncedCount()/sentTotal()/forgottenTotal()` 诊断。

### 访问方式

计划首选 AT；执行时发现 AT 对 **dev 编译类路径**不生效，直接调用仍报
`updateChunkTracking ... protected`，因此按计划允许的备选方案改为 Mixin `@Invoker`：

- 新增 `src/main/java/com/captureapi/core/mixin/ChunkMapAccessor.java`：
  ```java
  @Mixin(ChunkMap.class)
  public interface ChunkMapAccessor {
      @Invoker("updateChunkTracking")
      void captureapi$updateChunkTracking(ServerPlayer player, ChunkPos pos,
          MutableObject<ClientboundLevelChunkWithLightPacket> packetCache,
          boolean wasAdded, boolean wasRemoved);
  }
  ```
- 已加入 `captureapi.mixins.json` 的 `client` 列表；
- 构建 refmap 已生成 SRG 映射：
  ```json
  "com/captureapi/core/mixin/ChunkMapAccessor": {
    "updateChunkTracking": "m_183754_(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/ChunkPos;Lorg/apache/commons/lang3/mutable/MutableObject;ZZ)V"
  }
  ```
- `accesstransformer.cfg` 保持原空模板（未留下无效 AT 行）。

### CS2：接线/清理/health

- `CaptureChunkPlanner.capture()`：
  ```text
  line 136: SingleplayerTicketHolder.update(chunks);
  line 137: ChunkSyncRequester.update(chunks);
  ```
  同一 desired 集合，ticket 后立即 sync。
- `WorldLifecycleHandler` 退出任务：
  ```text
  line 72: SingleplayerTicketHolder.releaseAll();
  line 73: ChunkSyncRequester.releaseAll();
  line 74: CameraManager.get().clear();
  ```
- `/health` **`chunk_synced`** 字段已加（NetworkServiceManager 第 318 行）。
- `ChunkSyncRequester` 在 active 相机为空时清空 desired，删除相机后可
  由 tick 触发对相机区块的 forget。

### CS3：测试脚本

新增 `scripts/s11_chunk_sync_test.py`，流程：

1. 启动 dev 客户端并进入 `s1test`；
2. 清理旧相机，在玩家 `+200` 格、`y=py+30` 创建水平朝向玩家的相机；
3. 等待 **60s**（ticket+sync+首帧）；
4. 断言日志存在 `[CHUNK/SYNC] 已同步=`，且 `synced ≥ max(20, 60% × desired)`；
5. 通过 HTTP `/camera/{id}/frame` 取帧；
   - 用 ffmpeg 解码 raw RGB，按天空色 `(176,205,254)` 阈值 75 逐行计算非天空像素；
   - 断言画面下半部中“非天空像素 ≥60% 宽度”的行占比 ≥60%；
   - 同时运行 ffmpeg `signalstats` 采集 `YAVG` 辅助元数据；
6. 删除相机后等 15s，断言 `[CHUNK/SYNC] 卸载=` 出现且计数 >0；
7. 生成 `docs/evidence/S11/report.json` 与 `artifacts/far-terrain.jpg`、
   `pixel-analysis.json`。

运行方式（验收端）：

```powershell
cd E:\捕获接口
python scripts/s11_chunk_sync_test.py
```

脚本已通过 `python -m py_compile` 语法校验；本次未在游戏内运行（按任务说明“无法进游戏实测”）。

---

## 2. 静态验证

| 项 | 结果 |
|---|---|
| `./gradlew compileJava` / `scripts/build.ps1` | **24/24 PASS** |
| 构建日志 | `build/reports/env/build-20261104-011713.log` |
| magic audit | `literals=0, files=0` |
| jar | `build/libs/captureapi-0.5.0.jar`（401075 B） |
| jar 类 | `ChunkSyncRequester.class`、`ChunkMapAccessor.class`、`SingleplayerTicketHolder.class` |
| Mixin 配置 | `captureapi.mixins.json` client 列表含 `ChunkMapAccessor` |
| refmap | `m_183754_(...)` 已生成（见上） |
| 接线顺序 | planner ticket→sync；lifecycle 三项释放 |
| health | `chunk_synced` 已加 |
| 测试脚本 | `py_compile` 通过 |

**未运行游戏内实测**：没有 `[CHUNK/SYNC]` 运行时日志、没有实际远端地形帧；
用户验收端跑 `s11_chunk_sync_test.py` 后才能确认同步机制端到端效果。

---

## 3. 改动文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `src/main/java/com/captureapi/video/chunk/ChunkSyncRequester.java` | 新增 | sync diff/限速/发包/forget/上限/日志 |
| `src/main/java/com/captureapi/core/mixin/ChunkMapAccessor.java` | 新增 | `@Invoker updateChunkTracking`（SRG m_183754_） |
| `src/main/resources/captureapi.mixins.json` | 修改 | client 列表加入 accessor |
| `src/main/java/com/captureapi/video/chunk/CaptureChunkPlanner.java` | 修改 | ticket 后接入 sync |
| `src/main/java/com/captureapi/core/resource/WorldLifecycleHandler.java` | 修改 | 退出 releaseAll |
| `src/main/java/com/captureapi/net/NetworkServiceManager.java` | 修改 | `/health.chunk_synced` |
| `scripts/s11_chunk_sync_test.py` | 新增 | 验收脚本（+200格/像素/卸载） |
| `docs/evidence/S6/magic-audit.txt` | 刷新 | 构建审计 |

---

## 4. 关键 diff 摘要

```diff
+ // ChunkSyncRequester
+ private static final int SEND_PER_TICK = 8;
+ private static final int MAX_SYNCED = CaptureConstants.SINGLEPLAYER_MAX_CHUNK_TICKETS;
+ Desired/Synced sets + update diff + 2048 cap
+ server.execute(() -> apply(...))
+ ((ChunkMapAccessor) chunkMap).captureapi$updateChunkTracking(
+     player, pos, new MutableObject<>(), true, false);
+ if (!isChunkInRange(...)) player.connection.send(
+     new ClientboundForgetLevelChunkPacket(pos.x, pos.z));

+ // CaptureChunkPlanner
+ SingleplayerTicketHolder.update(chunks);
+ ChunkSyncRequester.update(chunks);

+ // WorldLifecycleHandler leave task
+ SingleplayerTicketHolder.releaseAll();
+ ChunkSyncRequester.releaseAll();
+ CameraManager.get().clear();

+ // NetworkServiceManager
+ json.addProperty("chunk_synced", ChunkSyncRequester.syncedCount());
```

---

## 5. 提交

- 代码/脚本提交：`5634cd9`
  - 消息：`feat:【区块同步机制】客户端按需同步相机区块`
- 本报告单独提交；最终报告提交 hash 以 `git log` 最新一条为准。

## 6. 未完成/风险

- 未做游戏内运行，实际同步效果、光照/BlockEntity 完整性、卸载后客户端
  `ClientLevel` 状态需验收端脚本确认；
- `synced` 在服务端发包任务提交时计入，未等待客户端回执；单机丢包面很小，
  但严格确认（`ClientLevel.getChunk`）未实现；
- 每 tick 8 块，297 块约 2s 发完；大视锥下带宽/客户端卡顿需实测；
- 玩家视距动态变化、跨维度切换未专项验证（forget 用实时 viewDistance；
  跨维度清理由世界退出路径兜底）；
- 远程 MP 不启用（与计划 D6 一致）。
