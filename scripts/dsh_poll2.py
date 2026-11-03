# -*- coding: utf-8 -*-
"""DSH 轮询 v2：提取非空内容 + 最新状态"""
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

# 会话当前状态（是否还在跑）
r = rpc('tools/call', {'name': 'list_sessions', 'arguments': {'query': 'Forge_Get_Test', 'limit': 1}}, 90, timeout=20)
sess = json.loads(r['result']['content'][0]['text'])['sessions'][0]
print(f"会话状态: running={sess.get('running')} updated={sess.get('updatedAt')}")

# 读最近消息（可能字段名不同，打印完整结构看一条）
r2 = rpc('tools/call', {'name': 'read_session', 'arguments': {'sessionId': SID, 'sinceSeq': 17600, 'limit': 50}}, 91, timeout=30)
data = json.loads(r2['result']['content'][0]['text'])
msgs = data.get('messages', [])
print(f'消息数: {len(msgs)}, cursor: {data.get("cursor")}')
if msgs:
    # 打印最后一条完整结构（截断）
    print('最后一条消息原始结构:')
    print(json.dumps(msgs[-1], ensure_ascii=False)[:1500])
