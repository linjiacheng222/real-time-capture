# -*- coding: utf-8 -*-
"""S10 缺陷修复验证实验：起游戏→进世界→远处相机取帧→录像音频→导出产物"""
import urllib.request, json, socket, base64, os, struct, time, subprocess, sys

TOKEN = 'dshmcp_qSrx3xwipBR8jaDhV7P9_7keFDJHMfKQaEpvKaNFOEM'
URL = 'http://127.0.0.1:61680/dsh-mcp/mcp'
WS_HOST, WS_PORT = '127.0.0.1', 25581
HTTP = 'http://127.0.0.1:25580'
EV = 'docs/evidence/S10'
os.makedirs(EV, exist_ok=True)
os.makedirs(f'{EV}/artifacts', exist_ok=True)
results = []

def log(name, ok, detail):
    results.append({'name': name, 'ok': ok, 'detail': str(detail)[:300]})
    print(f"{'PASS' if ok else 'FAIL'} {name}: {str(detail)[:200]}")

# ===== 手写 WS 客户端（复用 S8 验证过的） =====
def ws_send(msg, timeout=15, retries=3):
    last_err = None
    for attempt in range(retries):
        try:
            return _ws_once(msg, timeout)
        except (IndexError, IOError) as e:
            last_err = e
            time.sleep(1.5)
    raise last_err

def _ws_once(msg, timeout=15):
    s = socket.create_connection((WS_HOST, WS_PORT), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f'GET / HTTP/1.1\r\nHost: {WS_HOST}:{WS_PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
               f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n').encode())
    resp = b''
    while b'\r\n\r\n' not in resp: resp += s.recv(1024)
    if b'101' not in resp.split(b'\r\n')[0]: s.close(); raise IOError('handshake fail')
    payload = json.dumps(msg).encode()
    mask = os.urandom(4)
    header = b'\x81'
    ln = len(payload)
    if ln < 126: header += bytes([0x80 | ln])
    elif ln < 65536: header += bytes([0x80 | 126]) + struct.pack('>H', ln)
    else: header += bytes([0x80 | 127]) + struct.pack('>Q', ln)
    s.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))
    data = bytearray()
    def recv_more(n):
        while len(data) < n:
            c = s.recv(65536)
            if not c: raise IOError('closed')
            data.extend(c)
    recv_more(2)
    # 若首字节还没到（服务端可能先发 ping 等），继续等
    deadline = time.time() + timeout
    while time.time() < deadline:
        b1, b2 = data[0], data[1]
        op = b1 & 0xF; ln = b2 & 0x7F; idx = 2
        if ln == 126: recv_more(4); ln = struct.unpack('>H', bytes(data[2:4]))[0]; idx = 4
        elif ln == 127: recv_more(10); ln = struct.unpack('>Q', bytes(data[2:10]))[0]; idx = 10
        recv_more(idx + ln)
        p = bytes(data[idx:idx+ln]); del data[:idx+ln]
        if op == 1:
            s.close(); return json.loads(p.decode())
        elif op == 8: s.close(); raise IOError('close frame')
        elif op in (0, 2): continue

def wait_port(port, timeout=300):
    dl = time.time() + timeout
    while time.time() < dl:
        try:
            s = socket.create_connection(('127.0.0.1', port), timeout=2); s.close(); return True
        except Exception: time.sleep(4)
    return False

# ===== 阶段1：启动游戏 =====
print('===== 1. 启动 dev 客户端 =====')
try: os.rename('run/logs/latest.log', f'run/logs/latest-prev-s10-{int(time.time())}.log')
except FileNotFoundError: pass
env = dict(os.environ); env['JAVA_HOME'] = 'E:\\Tools\\jdk17'
for k in ('HTTP_PROXY','HTTPS_PROXY','http_proxy','https_proxy'): env.pop(k, None)
stdout = open(f'build/reports/env/s10-stdout.log', 'w')
proc = subprocess.Popen(['cmd','/c','gradlew.bat','runClient','--console=plain'],
    cwd='E:\\捕获接口', env=env, stdout=stdout, stderr=subprocess.STDOUT)
if not wait_port(25580, 420):
    log('boot', False, '端口 25580 未就绪（420s 超时）'); 
    json.dump({'phases': results}, open(f'{EV}/report.json','w',encoding='utf-8'), ensure_ascii=False, indent=2)
    proc.kill(); sys.exit(1)
log('boot+net', True, '端口 25580 就绪')

# 等主菜单
dl = time.time() + 120
menu = False
while time.time() < dl and not menu:
    try:
        raw = open('run/logs/latest.log','rb').read()
        text = raw.decode('gbk', errors='ignore')
        if 'Sound engine started' in text: menu = True
    except FileNotFoundError: pass
    if not menu: time.sleep(5)
log('main_menu', menu, f'SoundEngine={menu}')

# ===== 阶段2：进世界 =====
print('===== 2. 载入世界 =====')
r = ws_send({'id':'w','action':'client.loadWorld','params':{'name':'s1test'}}, timeout=30)
in_world = False
pose = None
dl = time.time() + 120
while time.time() < dl:
    time.sleep(5)
    try:
        p = ws_send({'id':'p','action':'client.playerPose'}, timeout=8)
        if p.get('ok') and p.get('result',{}).get('x') is not None:
            in_world = True; pose = p['result']; break
    except Exception: continue
log('enter_world', in_world, f'pose x={pose.get("x") if pose else None}')
if not in_world:
    json.dump({'phases': results}, open(f'{EV}/report.json','w',encoding='utf-8'), ensure_ascii=False, indent=2)
    proc.kill(); sys.exit(1)
px, py, pz = pose['x'], pose['y'], pose['z']

# ===== 阶段3：远处相机取帧（缺陷1 验证） =====
print('===== 3. 远处相机（缺陷1：区块强加载） =====')
# 清残留
try:
    lst = ws_send({'id':'cl','action':'camera.list'}, timeout=8)
    for c in lst['result']['cameras']:
        ws_send({'id':'del','action':'camera.delete','params':{'id':c['id']}}, timeout=8)
except Exception: pass

# 玩家位置 +200 格远处（玩家视距外），水平朝向玩家方向
far_x = px + 200
r = ws_send({'id':'c1','action':'camera.create','params':{'x':far_x,'y':py+30,'z':pz,'yaw':180,'pitch':10,'fov':70}}, timeout=15)
log('far_camera_create', r.get('ok', False), f'pos=({far_x:.0f},{py+30:.0f},{pz:.0f}) yaw=180 朝玩家')

# 等 ticket 生效+区块加载+首帧
print('等待区块加载与首帧（45s）...')
time.sleep(45)

# 取帧——用 capture（等待该路渲帧）
r = ws_send({'id':'cap','action':'capture','params':{'camera':1,'out':f'{EV}/artifacts/far-terrain.jpg'}}, timeout=30)
ok = r.get('ok', False)
size = r.get('result',{}).get('sizeBytes', 0) if ok else 0
log('far_capture', ok and size > 5000, f'{size}B' if ok else r.get('error'))

# 再取一张 HTTP 的
import urllib.request
try:
    with urllib.request.urlopen(f'{HTTP}/camera/1/frame', timeout=8) as resp:
        img = resp.read()
        with open(f'{EV}/artifacts/far-terrain-http.jpg','wb') as f: f.write(img)
        log('far_http_frame', resp.status==200 and len(img)>5000, f'{len(img)}B ts={resp.headers.get("X-Frame-Timestamp","")}')
except Exception as e:
    log('far_http_frame', False, str(e))

# ===== 阶段4：录像含音频（缺陷2 验证） =====
print('===== 4. 录像音频（缺陷2） =====')
r = ws_send({'id':'rec','action':'recording.start','params':{'camera':1,'fps':15,'audio':True}}, timeout=15)
log('rec_start', r.get('ok', False), r.get('result', ''))
time.sleep(14)
r = ws_send({'id':'recs','action':'recording.status'}, timeout=8)
frames = r.get('result',{}).get('frames', 0) if r.get('ok') else 0
try:
    r2 = ws_send({'id':'recx','action':'recording.stop'}, timeout=25)
except Exception:
    time.sleep(5)
    r2 = ws_send({'id':'recx2','action':'recording.status'}, timeout=8)
rec_frames = r2.get('result',{}).get('frames', frames)
audio_ok = r2.get('result',{}).get('audioAvailable', False)
rec_path = r2.get('result',{}).get('path','')
log('rec_stop', rec_frames > 50 and audio_ok, f'frames={rec_frames} audio={audio_ok}')

# ffprobe 验证音轨
if rec_path and os.path.exists(rec_path):
    import shutil
    ffprobe = 'E:\\Tools\\ffmpeg\\bin\\ffprobe.exe'
    out = subprocess.run([ffprobe,'-v','error','-show_streams',rec_path], capture_output=True, text=True, timeout=15)
    has_audio = 'codec_name=aac' in out.stdout
    log('rec_audio_track', has_audio, 'AAC 音轨存在' if has_audio else '无音轨！')
    shutil.copy(rec_path, f'{EV}/artifacts/recording-with-audio.mp4')

# ===== 阶段5：相机上限（缺陷3 验证） =====
print('===== 5. 相机上限（缺陷3） =====')
ws_send({'id':'d2','action':'camera.delete','params':{'id':1}}, timeout=8)
created = 0; third_rejected = False
for i in range(3):
    r = ws_send({'id':f'cc{i}','action':'camera.create','params':{'x':px+i*10,'y':py,'z':pz}}, timeout=10)
    if r.get('ok'): created += 1
    else:
        code = r.get('error',{}).get('code','')
        if code == 'E_RATE_LIMIT': third_rejected = True
log('camera_limit', created == 2 and third_rejected, f'成功创建={created}，第3台被拒={third_rejected}')

# 世界退出后再验证清空
try: ws_send({'id':'lw','action':'client.leaveWorld'}, timeout=15)
except Exception: pass
time.sleep(8)
r = ws_send({'id':'cl2','action':'camera.list'}, timeout=10)
remaining = len(r.get('result',{}).get('cameras',[]))
log('world_exit_clear', remaining == 0, f'退出后残留={remaining}')

# ===== 收尾 =====
proc.kill()
time.sleep(3)
subprocess.run(['taskkill','/F','/IM','java.exe'], capture_output=True)
time.sleep(2)

report = {
    'date': time.strftime('%Y-%m-%dT%H:%M:%S'),
    'phases': results,
    'summary': f"{sum(1 for x in results if x['ok'])}/{len(results)}",
    'artifacts': os.listdir(f'{EV}/artifacts'),
}
json.dump(report, open(f'{EV}/report.json','w',encoding='utf-8'), ensure_ascii=False, indent=2)
passed = sum(1 for x in results if x['ok'])
print(f'\n===== S10 验证完成：PASS {passed}/{len(results)} =====')
print(f'报告: {EV}/report.json')
print(f'产物: {EV}/artifacts/')
