# -*- coding: utf-8 -*-
"""DSH ask_dsh 长等待：轮询区块同步任务直到完成（ask 语义=单次调用等到本轮结束）"""
import urllib.request, json, sys, time

TOKEN = 'dshmcp_qSrx3xwipBR8jaDhV7P9_7keFDJHMfKQaEpvKaNFOEM'
URL = 'http://127.0.0.1:61680/dsh-mcp/mcp'
SID = 'session-b180bb99-f7c3-47b4-a09b-1b3e590f5ec6'

def rpc(method, params, msg_id, timeout=30):
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

def call_tool(name, args, msg_id, timeout=30):
    return rpc('tools/call', {'name': name, 'arguments': args}, msg_id, timeout)

# ask_dsh：发送"报告进度"消息并阻塞等本轮结束（timeoutMs 给足）
# HTTP 层超时需大于 ask 的 timeoutMs
ASK_TIMEOUT_MS = 10 * 60 * 1000  # 10 分钟
msg_id = 200
try:
    print(f'[{time.strftime("%H:%M:%S")}] ask_dsh 等待区块同步任务（最长 10 分钟）...')
    r = call_tool('ask_dsh', {
        'sessionId': SID,
        'message': '【进度询问】你正在执行客户端区块同步机制任务（requestId 559181ec）。请继续完成全部工作（实现→构建24/24→写测试脚本→提交→S11报告），完成后用一段话报告：commit hash、改动文件数、构建结果、测试脚本路径。不要中断正在进行的工作——如果你还在中途，就先做完再回复这段报告。',
        'timeoutMs': ASK_TIMEOUT_MS
    }, msg_id, timeout=ASK_TIMEOUT_MS / 1000 + 60)
    text = r['result']['content'][0]['text']
    data = json.loads(text)
    print(f"status: {data.get('status')}")
    print(f"reply:\n{data.get('reply', '(null)')[:2000]}")
    cursor = data.get('cursor', {})
    print(f"cursor: lastSeq={cursor.get('lastSeq')}")
except Exception as e:
    print(f'ask 异常: {e}')
    # 回退：读会话尾部
    r = rpc('tools/call', {'name': 'read_session', 'arguments': {'sessionId': SID, 'limit': 30}}, 201, timeout=30)
    data = json.loads(r['result']['content'][0]['text'])
    msgs = data.get('messages', [])
    print(f'\n=== 回退读取（最近 {len(msgs)} 条）===')
    for m in msgs[-6:]:
        if m.get('role') == 'assistant' and m.get('text'):
            print(f"--- seq={m.get('seq')} ---")
            print(m['text'][:800])
