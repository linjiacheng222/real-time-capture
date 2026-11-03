# -*- coding: utf-8 -*-
"""DSH 任务派发：三缺陷修复"""
import urllib.request, json, sys

TOKEN = 'dshmcp_qSrx3xwipBR8jaDhV7P9_7keFDJHMfKQaEpvKaNFOEM'
URL = 'http://127.0.0.1:61680/dsh-mcp/mcp'
SID = 'session-b180bb99-f7c3-47b4-a09b-1b3e590f5ec6'

def rpc(method, params, msg_id, timeout=600):
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

def call_tool(name, args, msg_id, timeout=600):
    return rpc('tools/call', {'name': name, 'arguments': args}, msg_id, timeout)

TASK = r"""你是 Minecraft Forge 1.20.1 模组【捕获接口】的修复执行端。工程在 E:\捕获接口（ForgeGradle 6，Mojmap，JDK17=E:\Tools\jdk17，构建=scripts\build.ps1 需 24/24 PASS）。修复以下三个已定位根因的缺陷，全部用绝对路径操作工程。

## 缺陷1（P0）单机相机区块强加载未实现
现象：相机水平朝向只拍到天空（目标区块未加载）。根因：src/main/java/com/captureapi/video/chunk/CaptureChunkPlanner.java 只做几何求取；src/main/java/com/captureapi/net/RemoteCaptureClient.java 第127行单机时 no-op。
修复：新建 src/main/java/com/captureapi/video/chunk/SingleplayerTicketHolder.java：
- Minecraft.getInstance().getSingleplayerServer() 非空时启用；
- TicketType.create("captureapi_camera", Comparator.comparing(ChunkPos::asLong))，级别 ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)（参照 src/servercomponent/java/com/captureapi/server/TicketManager.java 的同款做法）；
- 在 CaptureChunkPlanner.capture() 末尾调用 update(Set<Long> chunks)：diff 旧集合，新增 addRegionTicket、移除 removeRegionTicket，必须经集成服 server.execute(...) 投递到服务端线程；
- 世界退出时释放全部（可挂 core/resource/WorldLifecycleHandler 的退出路径，或 tick 检查 level==null 时清理）；
- 上限守卫：同时持有的 ticket 总数不超过 2048，超出截断并 WARN。

## 缺陷2（P0）录像音频 audioChunks=0
现象：默认配置（audio_multi_listener_enabled=false）下 recording.start camera:1 audio:true 录 12 秒，audioChunks=0、mp4 无音轨。
根因定位：src/main/java/com/captureapi/net/recording/RecordingSession.java 的 audioLoop()——audioChannelId=-1 时调 AudioChannelRegistry.snapshotChannel(primaryChannelId(),1)=snapshotChannel(0,1)→AudioRingBuffer.get().snapshotRecent(1)。请先在 audioLoop 的 chunk==null 分支加 nullCounts 计数日志定位（读 src/main/java/com/captureapi/audio/AudioRingBuffer.java 的 snapshotRecent 找返回 null 的条件），修复使默认配置下能持续取到 PCM（可加 peekLatest 兜底），最终 mp4 必须含 AAC 音轨（FFmpeg 合流逻辑已在，修取数即可）。

## 缺陷3（P1）max_cameras 上限绕过
现象：跨轮测试 camera.list 出现 3 台而 max_cameras=2。修复：世界退出时清空 CameraManager（src/main/java/com/captureapi/core/resource/WorldLifecycleHandler.java 挂 LoggingOut/LevelUnload，调 CameraManager 的清空方法；若 CameraManager 无 clearAll 则补一个）。

## 流程与验收
1) 修完跑 E:\捕获接口\scripts\build.ps1（用 PowerShell 执行，JAVA_HOME=E:\Tools\jdk17），必须 24/24 PASS；
2) 无法进游戏实测没关系——把三个修复点各自的静态验证写进报告（改动文件清单＋关键 diff 摘要）；
3) git add 相关文件并 commit（信息含【缺陷1/2/3修复】），报告 commit hash；
4) 全部结果写入 E:\捕获接口\docs\evidence\S9\fix-report.md。
先读 CaptureChunkPlanner.java、RemoteCaptureClient.java、RecordingSession.java、AudioRingBuffer.java、WorldLifecycleHandler.java、CameraManager.java、TicketManager.java（服务端组件），再动手。"""

r = call_tool('send_message', {'sessionId': SID, 'message': TASK, 'wait': False}, 70, timeout=30)
text = r['result']['content'][0]['text']
print('任务已发送:', text[:250])
data = json.loads(text)
print('cursor.lastSeq =', data.get('cursor', {}).get('lastSeq'))
