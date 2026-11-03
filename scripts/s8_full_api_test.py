# -*- coding: utf-8 -*-
"""S8 全量指令实测——纯标准库实现（HTTP + 手写 WebSocket RFC6455 客户端）"""
import json, socket, base64, os, struct, hashlib, time, urllib.request

WS_HOST, WS_PORT = '127.0.0.1', 25581
HTTP = 'http://127.0.0.1:25580'
EV = 'docs/evidence/S8/full-api'
os.makedirs(EV, exist_ok=True)
results = []

def ws_send(msg, timeout=12):
    """手写 RFC6455：单连接单消息（简单可靠）"""
    s = socket.create_connection((WS_HOST, WS_PORT), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    handshake = (f'GET / HTTP/1.1\r\nHost: {WS_HOST}:{WS_PORT}\r\n'
                 f'Upgrade: websocket\r\nConnection: Upgrade\r\n'
                 f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n')
    s.sendall(handshake.encode())
    resp = b''
    while b'\r\n\r\n' not in resp:
        resp += s.recv(1024)
    if b'101' not in resp.split(b'\r\n')[0]:
        s.close(); raise IOError('WS handshake failed: ' + resp[:80].decode(errors='ignore'))
    payload = json.dumps(msg).encode()
    # client→server 必须掩码
    mask = os.urandom(4)
    header = b'\x81'
    ln = len(payload)
    if ln < 126: header += bytes([0x80 | ln])
    elif ln < 65536: header += bytes([0x80 | 126]) + struct.pack('>H', ln)
    else: header += bytes([0x80 | 127]) + struct.pack('>Q', ln)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    s.sendall(header + mask + masked)
    # 读一帧文本（服务端不掩码）；先读完握手残留再补收
    data = bytearray()

    def recv_more(min_len):
        while len(data) < min_len:
            chunk = s.recv(65536)
            if not chunk: raise IOError('WS closed mid-frame')
            data.extend(chunk)

    recv_more(2)
    while True:
        b1, b2 = data[0], data[1]
        opcode = b1 & 0x0F
        ln = b2 & 0x7F
        idx = 2
        if ln == 126:
            recv_more(4); ln = struct.unpack('>H', bytes(data[2:4]))[0]; idx = 4
        elif ln == 127:
            recv_more(10); ln = struct.unpack('>Q', bytes(data[2:10]))[0]; idx = 10
        recv_more(idx + ln)
        payload_out = bytes(data[idx:idx + ln])
        del data[:idx + ln]
        if opcode == 1:
            s.close()
            return json.loads(payload_out.decode())
        elif opcode == 8:
            s.close(); raise IOError('WS close frame')
        elif opcode in (0, 2):
            continue  # 分片/二进制控制帧：跳过继续读下一帧

def test(name, action, params=None, check=None):
    try:
        r = ws_send({'id': name, 'action': action, 'params': params or {}})
        ok = r.get('ok', False)
        detail = r.get('result', r.get('error', ''))
        if ok and check:
            ok = check(r)
        results.append({'name': name, 'ok': ok, 'detail': str(detail)[:200]})
        print(f"{'PASS' if ok else 'FAIL'} {name}: {str(detail)[:150]}")
        return r
    except Exception as e:
        results.append({'name': name, 'ok': False, 'detail': str(e)[:200]})
        print(f"FAIL {name}: {e}")
        return None

def http_get(path, save=None):
    try:
        req = urllib.request.Request(HTTP + path)
        with urllib.request.urlopen(req, timeout=8) as resp:
            body = resp.read()
            if save:
                with open(save, 'wb') as f: f.write(body)
            return resp.status, dict(resp.headers), len(body)
    except Exception as e:
        return 0, {'error': str(e)}, 0

print('===== 0. 环境确认 =====')
st, hd, sz = http_get('/health')
health = None
if st == 200:
    import urllib.request as ur
    health = json.loads(ur.urlopen(HTTP + '/health', timeout=8).read())
    results.append({'name': 'http.health', 'ok': True, 'detail': f'rendered={health.get("video_rendered_frames")}, audio={health.get("audio_loopback")}'})
    print(f"PASS http.health: rendered={health.get('video_rendered_frames')} audio_loopback={health.get('audio_loopback')}")
else:
    results.append({'name': 'http.health', 'ok': False, 'detail': str(hd)}); print(f"FAIL http.health: {hd}")

print('===== 1. 相机管理 =====')
r1 = test('camera.create#1', 'camera.create', {'x': 100, 'y': 64, 'z': 200, 'fov': 70})
r2 = test('camera.create#2', 'camera.create', {'x': 300, 'y': 70, 'z': 100, 'yaw': 180, 'fov': 90})
test('camera.adjust', 'camera.adjust', {'id': 2, 'yaw': 90})
test('camera.pause', 'camera.pause', {'id': 2})
test('camera.resume', 'camera.resume', {'id': 2})
test('camera.list', 'camera.list', check=lambda r: len(r['result']['cameras']) >= 2)
test('camera.status', 'camera.status')
test('camera.delete', 'camera.delete', {'id': 2})
# 重建 2 号（后续测试用）
ws_send({'id': 're', 'action': 'camera.create', 'params': {'x': 300, 'y': 70, 'z': 100, 'yaw': 180, 'fov': 90}})
time.sleep(3)

print('===== 2. 统一截取 =====')
r = test('capture(default)', 'capture', {'out': 'run/evidence-s8-ws.jpg'})
if r and r['ok']:
    p = r['result']['location']
    if os.path.exists(p):
        sz = os.path.getsize(p)
        results.append({'name': 'capture.file_exists', 'ok': sz > 5000, 'detail': f'{p} = {sz}B'})
        print(f"{'PASS' if sz > 5000 else 'FAIL'} capture.file_exists: {sz}B")
r = test('capture(cam1+gray)', 'capture', {'camera': 1, 'filter': 'grayscale', 'quality': 90, 'out': 'run/evidence-s8-gray.jpg'})
r = test('capture(audio)', 'capture', {'audio': True, 'audioChunks': 5, 'out': 'run/evidence-s8-aud.jpg'},
         check=lambda r: r['result'].get('audio', {}).get('sampleCount', 0) > 0)

print('===== 3. 游戏指令（client.command 代理） =====')
for name, cmd in [
    ('cmd.camera.list', 'captureapi camera list'),
    ('cmd.capture.gray', 'captureapi capture filter:grayscale quality:88 out:run/evidence-s8-cmd.jpg'),
    ('cmd.capture.cam2', 'captureapi capture camera:2 out:run/evidence-s8-cam2.jpg'),
    ('cmd.filter.list', 'captureapi filter list'),
    ('cmd.overlay.list', 'captureapi overlay list'),
    ('cmd.config.get', 'captureapi info config fps_max'),
    ('cmd.health', 'captureapi info health'),
    ('cmd.frame', 'captureapi info frame'),
    ('cmd.shader', 'captureapi info shader'),
    ('cmd.help', 'captureapi help'),
    ('cmd.renderfilter', 'captureapi renderfilter list'),
    ('cmd.audiofilter', 'captureapi audiofilter list'),
    ('cmd.record.status', 'captureapi record status'),
]:
    test(name, 'client.command', {'command': cmd})

print('===== 4. 多路视频 =====')
test('video.subscribe', 'video.subscribe', {'cameras': [1]})
time.sleep(4)
test('video.unsubscribe', 'video.unsubscribe', {})

print('===== 5. 多监听音频 =====')
test('audio.channels.list', 'audio.channels.list')
r = test('audio.channels.peek', 'audio.channels.peek', {'channel': 0})
test('audio.subscribe', 'audio.subscribe', {'channels': [0]})
time.sleep(3)
test('audio.unsubscribe', 'audio.unsubscribe', {})
test('audio.section', 'audio.section', {'x': 100, 'y': 64, 'z': 200})

print('===== 6. 滤镜/名单/参数/光影 =====')
r = test('filter.list', 'filter.list')
test('filter.set(gray)', 'filter.set', {'name': 'grayscale'})
test('filter.set(none)', 'filter.set', {'name': ''})
test('filter.reload', 'filter.reload')
test('overlay.list', 'overlay.list')
test('overlay.reload', 'overlay.reload')
test('renderfilter.list', 'renderfilter.list')
test('renderfilter.reload', 'renderfilter.reload')
test('audiofilter.list', 'audiofilter.list')
test('audiofilter.reload', 'audiofilter.reload')
test('shader.status', 'shader.status')
r = test('config.get(fps)', 'config.get', {'key': 'fps_max'})
test('config.set(15)', 'config.set', {'key': 'fps_max', 'value': 15})
test('config.set(30)', 'config.set', {'key': 'fps_max', 'value': 30})

print('===== 7. 录像 =====')
r = test('recording.start(cam1+audio)', 'recording.start', {'camera': 1, 'fps': 15, 'audio': True})
time.sleep(12)
r = test('recording.stop', 'recording.stop')
if r and r['ok']:
    p = r['result'].get('path', '')
    if p and os.path.exists(p):
        sz = os.path.getsize(p)
        results.append({'name': 'recording.file', 'ok': sz > 1000, 'detail': f'{p} = {sz}B, frames={r["result"]["frames"]}'})
        print(f"{'PASS' if sz > 1000 else 'FAIL'} recording.file: {sz}B frames={r['result']['frames']}")

print('===== 8. HTTP =====')
st, hd, sz = http_get('/camera/1/frame', save=os.path.join(EV, 'http-frame-cam1.jpg'))
results.append({'name': 'http.frame', 'ok': st == 200 and sz > 5000, 'detail': f'status={st} {sz}B ts={hd.get("X-Frame-Timestamp","")}'})
print(f"{'PASS' if st==200 and sz>5000 else 'FAIL'} http.frame: {st} {sz}B")

print('===== 9. 世界信息确认 =====')
r = test('client.playerPose', 'client.playerPose')

print()
passed = sum(1 for x in results if x['ok'])
total = len(results)
print(f'===== 总计：PASS {passed} / {total} =====')
with open(os.path.join(EV, 'full-api-report.json'), 'w', encoding='utf-8') as f:
    json.dump({'date': time.strftime('%Y-%m-%dT%H:%M:%S'), 'phases': results,
               'summary': f'{passed}/{total}'}, f, ensure_ascii=False, indent=2)
print(f'报告已写入 {EV}/full-api-report.json')
