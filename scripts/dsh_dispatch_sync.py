# -*- coding: utf-8 -*-
"""DSH 派发：客户端区块同步机制（T2.7.2-bis）"""
import urllib.request, json, sys

TOKEN = 'dshmcp_qSrx3xwipBR8jaDhV7P9_7keFDJHMfKQaEpvKaNFOEM'
URL = 'http://127.0.0.1:61680/dsh-mcp/mcp'
SID = 'session-b180bb99-f7c3-47b4-a09b-1b3e590f5ec6'

def rpc(method, params, msg_id, timeout=120):
    body = json.dumps({'jsonrpc': '2.0', 'id': msg_id, 'method': method, 'params': params}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        'Content-Type': 'application/json',
        'Authorization': f'Bearer {TOKEN}',
        'Accept': 'application/json, text/event-stream'
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read().decode()
    if 'data:' in raw[:30]:
        for line in raw.splitlines():
            if line.startswith('data:'):
                return json.loads(line[5:].strip())
    return json.loads(raw)

def call_tool(name, args, msg_id, timeout=120):
    return rpc('tools/call', {'name': name, 'arguments': args}, msg_id, timeout)

TASK = r"""新任务：按完整计划文档实现【客户端区块按需同步机制】。计划全文在 E:\捕获接口\docs\客户端区块同步机制落地计划.md——先读它，再动手。要点摘要：

## 背景（S10 实测已确认）
你上次修的 SingleplayerTicketHolder 工作正常（服务端区块已加载），但客户端渲染需要数据同步到 ClientLevel——原版只同步玩家视距 12 区块，相机 +200 格处客户端无 LevelChunk，画面只有视距重叠区有内容。

## 核心 API（已从 recomp.jar 反编译核验签名）
- ChunkMap.updateChunkTracking(ServerPlayer, ChunkPos, MutableObject<ClientboundLevelChunkWithLightPacket>, boolean wasAdded, boolean wasRemoved) — protected，发区块给玩家的原版正式通道（含光照/BlockEntity）
- ChunkMap.isChunkInRange(int,int,int,int,int) — public static，视距判定
- ClientboundForgetLevelChunkPacket — 卸载
建议用 AT（accesstransformer.cfg 追加 SRG 名）解锁 updateChunkTracking；SRG 名用 javap 反查 recomp jar 或 Mixin@Invoker 二选一（AT 优先，计划 D2）。

## 实现清单（详见计划 CS1~CS3）
1. 新建 src/main/java/com/captureapi/video/chunk/ChunkSyncRequester.java：
   - update(Set<Long> desired)：diff synced；toSend 每服务端 tick 限 8 块经 server.execute 调 chunkMap.updateChunkTracking(player,pos,new MutableObject<>(),true,false)；toForget 仅当区块不在 desired 且不在玩家视距时发 ClientboundForgetLevelChunkPacket；
   - 计数日志 [CHUNK/SYNC] 入队/已同步/卸载；上限 2048。
2. CaptureChunkPlanner.capture() 末尾在 SingleplayerTicketHolder.update(chunks) 后加 ChunkSyncRequester.update(chunks)。
3. WorldLifecycleHandler 退出路径加 ChunkSyncRequester.releaseAll()；/health 加 chunk_synced 字段。
4. 构建必须 24/24 PASS（scripts/build.ps1，JAVA_HOME=E:\Tools\jdk17）。
5. 无法进游戏实测——静态验证写入报告；但请写好测试脚本 scripts/s11_chunk_sync_test.py（仿 scripts/s10_fix_verification.py：进世界→相机+200格→等60s→HTTP取帧→ffmpeg signalstats 逐行非天空像素占比≥60%断言→删相机验证卸载日志），供我这边跑。
6. git commit（信息含【区块同步机制】），报告 commit hash 与改动文件清单到 docs/evidence/S11/fix-report.md（可先建目录）。
注意：Mojmap 名在 dev 源直接用（工程已是 official 映射）；AT 里写 SRG 名（m_xxxxx），不确定就用 Mixin@Invoker。"""

r = call_tool('send_message', {'sessionId': SID, 'message': TASK, 'wait': False}, 100, timeout=30)
text = r['result']['content'][0]['text']
print('任务已发送:', text[:250])
