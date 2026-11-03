# -*- coding: utf-8 -*-
"""DSH 轮询 v3：读取完整最终回复"""
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

r2 = rpc('tools/call', {'name': 'read_session', 'arguments': {'sessionId': SID, 'sinceSeq': 17630, 'limit': 20}}, 91, timeout=30)
data = json.loads(r2['result']['content'][0]['text'])
msgs = data.get('messages', [])
for m in msgs:
    if m.get('role') == 'assistant' and m.get('text'):
        print(m['text'])
        print('\n' + '='*60 + '\n')
