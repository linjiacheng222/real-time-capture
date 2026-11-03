# -*- coding: utf-8 -*-
"""DSH 轮询：读取任务执行结果"""
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

# 读取任务发送后（baseline 17295）的会话内容
r = call_tool('read_session', {'sessionId': SID, 'sinceSeq': 17295, 'limit': 100}, 80, timeout=30)
text = r['result']['content'][0]['text']
data = json.loads(text)
msgs = data.get('messages', [])
print(f'=== 自 17295 起共 {len(msgs)} 条消息，最后 seq={data.get("cursor",{}).get("lastSeq")} ===')
for m in msgs[-15:]:
    role = m.get('role', '?')
    content = m.get('content', '')
    if isinstance(content, list):
        content = ' '.join(str(c.get('text','') if isinstance(c,dict) else c)[:150] for c in content)
    print(f'--- [{role}] seq={m.get("seq")} ---')
    print(str(content)[:400])
