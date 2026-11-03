# -*- coding: utf-8 -*-
"""S11 客户端区块按需同步验证：起游戏→+200格相机→等待同步→HTTP取帧→像素断言→卸载日志"""
import os, sys, json, time, socket, base64, struct, subprocess, urllib.request

REPO = r'E:\捕获接口'
WS_HOST, WS_PORT = '127.0.0.1', 25581
HTTP = 'http://127.0.0.1:25580'
EV = os.path.join(REPO, 'docs', 'evidence', 'S11')
os.makedirs(os.path.join(EV, 'artifacts'), exist_ok=True)
RESULTS = []

# 禁用系统代理（本机代理会劫持 127.0.0.1 请求 → 502）
_proxy_handler = urllib.request.ProxyHandler({})
_opener = urllib.request.build_opener(_proxy_handler)
urllib.request.install_opener(_opener)

def log(name, ok, detail):
    RESULTS.append({'name': name, 'ok': bool(ok), 'detail': str(detail)[:500]})
    print(f"{'PASS' if ok else 'FAIL'} {name}: {str(detail)[:240]}")

def ws_send(msg, timeout=20, retries=3):
    last = None
    for _ in range(retries):
        try:
            return _ws_once(msg, timeout)
        except Exception as e:
            last = e
            time.sleep(1.5)
    raise last

def _ws_once(msg, timeout):
    s = socket.create_connection((WS_HOST, WS_PORT), timeout=timeout)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f'GET / HTTP/1.1\r\nHost: {WS_HOST}:{WS_PORT}\r\nUpgrade: websocket\r\n'
               f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n').encode())
    resp = b''
    while b'\r\n\r\n' not in resp:
        chunk = s.recv(1024)
        if not chunk:
            raise IOError('handshake closed')
        resp += chunk
    if b'101' not in resp.split(b'\r\n')[0]:
        s.close()
        raise IOError('handshake fail')
    payload = json.dumps(msg).encode()
    mask = os.urandom(4)
    header = b'\x81'
    ln = len(payload)
    if ln < 126:
        header += bytes([0x80 | ln])
    elif ln < 65536:
        header += bytes([0x80 | 126]) + struct.pack('>H', ln)
    else:
        header += bytes([0x80 | 127]) + struct.pack('>Q', ln)
    s.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))
    data = bytearray()
    def recv_more(n):
        while len(data) < n:
            c = s.recv(65536)
            if not c:
                raise IOError('closed')
            data.extend(c)
    deadline = time.time() + timeout
    while time.time() < deadline:
        recv_more(2)
        b1, b2 = data[0], data[1]
        op, ln, idx = b1 & 0xF, b2 & 0x7F, 2
        if ln == 126:
            recv_more(4); ln = struct.unpack('>H', bytes(data[2:4]))[0]; idx = 4
        elif ln == 127:
            recv_more(10); ln = struct.unpack('>Q', bytes(data[2:10]))[0]; idx = 10
        recv_more(idx + ln)
        p = bytes(data[idx:idx + ln]); del data[:idx + ln]
        if op == 1:
            s.close()
            return json.loads(p.decode())
        if op == 8:
            s.close()
            raise IOError('close frame')
    s.close()
    raise IOError('ws timeout')

def wait_port(port, timeout=420):
    dl = time.time() + timeout
    while time.time() < dl:
        try:
            s = socket.create_connection(('127.0.0.1', port), timeout=2)
            s.close()
            return True
        except Exception:
            time.sleep(4)
    return False

def find_ffmpeg():
    for c in (r'E:\Tools\ffmpeg\bin\ffmpeg.exe', 'ffmpeg', 'ffmpeg.exe'):
        if os.path.exists(c) or (not os.path.isabs(c) and c == 'ffmpeg'):
            return c
    return 'ffmpeg'


def jpeg_size(path):
    """从 JPEG SOF 段读取宽高（不依赖 ffprobe）。"""
    data = open(path, 'rb').read()
    i = 2
    while i + 9 < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0xD9):
            i += 2
            continue
        seg_len = (data[i + 2] << 8) | data[i + 3]
        if marker in (0xC0, 0xC1, 0xC2, 0xC3):
            h = (data[i + 5] << 8) | data[i + 6]
            w = (data[i + 7] << 8) | data[i + 8]
            return w, h
        i += 2 + seg_len
    raise RuntimeError('JPEG 尺寸解析失败')

def read_log():
    # 日志实际为 GBK（Windows 控制台默认）：utf-8+ignore 会静默乱码导致中文匹配失败，
    # 因此直接用 GBK 家族解码（gb18030 兼容 GBK）
    for enc in ('gb18030', 'gbk', 'utf-8'):
        try:
            return open(os.path.join(REPO, 'run', 'logs', 'latest.log'), 'r', encoding=enc, errors='ignore').read()
        except OSError:
            return ''
    return ''

def log_values(text):
    import re
    sync = [(int(a), int(b)) for a, b in re.findall(
        r'\[CHUNK/SYNC\].*?已同步=(\d+).*?desired=(\d+)', text)]
    unload = [int(x) for x in re.findall(r'\[CHUNK/SYNC\].*?卸载=(\d+)', text)]
    return sync, unload

def analyze_frame(path):
    """用 ffmpeg 解码 raw RGB，逐行统计非天空像素比例。"""
    w, h = jpeg_size(path)
    ff = find_ffmpeg()
    raw = subprocess.run([ff, '-v', 'error', '-i', path, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                         capture_output=True, timeout=45)
    data = raw.stdout
    if len(data) < w * h * 3:
        raise RuntimeError(f'raw 帧长度不足 {len(data)} < {w*h*3}')
    # 天空色 176,205,254；逐行计算非天空像素占比
    sky = (176, 205, 254)
    rows = []
    for y in range(h):
        base = y * w * 3
        non_sky = 0
        for x in range(w):
            i = base + x * 3
            r, g, b = data[i], data[i + 1], data[i + 2]
            if abs(r - sky[0]) + abs(g - sky[1]) + abs(b - sky[2]) > 75:
                non_sky += 1
        rows.append(non_sky / float(w))
    lower = rows[h // 2:]
    hit = sum(1 for v in lower if v >= 0.60)
    # signalstats 作为辅助元数据
    stats = subprocess.run([ff, '-hide_banner', '-i', path, '-vf', 'signalstats',
                            '-f', 'null', '-'], capture_output=True, text=True, timeout=30)
    yavg = None
    for line in stats.stderr.splitlines():
        if 'YAVG:' in line:
            yavg = line.split('YAVG:')[-1].strip()
    return {'width': w, 'height': h, 'lower_rows': len(lower),
            'lower_rows_non_sky_60': hit,
            'lower_row_ratio': round(hit / max(1, len(lower)), 3),
            'all_rows_avg_non_sky': round(sum(rows) / max(1, len(rows)), 3),
            'signalstats_YAVG': yavg}

def main():
    print('===== 1. 启动 dev 客户端 =====')
    from pathlib import Path
    log_path = Path(REPO) / 'run' / 'logs' / 'latest.log'
    if log_path.exists():
        try:
            log_path.rename(log_path.with_name(f'latest-prev-s11-{int(time.time())}.log'))
        except OSError:
            pass
    env = dict(os.environ)
    env['JAVA_HOME'] = r'E:\Tools\jdk17'
    for k in ('HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy'):
        env.pop(k, None)
    stdout = open(os.path.join(REPO, 'build', 'reports', 'env', 's11-stdout.log'), 'w')
    proc = subprocess.Popen(['cmd', '/c', 'gradlew.bat', 'runClient', '--console=plain'],
                            cwd=REPO, env=env, stdout=stdout, stderr=subprocess.STDOUT)
    if not wait_port(25580, 420):
        log('boot', False, '端口 25580 未就绪（420s）')
        json.dump({'phases': RESULTS}, open(os.path.join(EV, 'report.json'), 'w', encoding='utf-8'),
                  ensure_ascii=False, indent=2)
        proc.kill(); sys.exit(1)
    log('boot+net', True, 'HTTP/WS 端口就绪')
    try:
        r = ws_send({'id': 'lw', 'action': 'client.loadWorld', 'params': {'name': 'clean_s12'}}, timeout=40)
        log('load_world', r.get('ok', False), r)
    except Exception as e:
        log('load_world', False, e)

    # 等进入世界
    pose = None
    dl = time.time() + 120
    while time.time() < dl:
        time.sleep(4)
        try:
            p = ws_send({'id': 'p', 'action': 'client.playerPose'}, timeout=8)
            if p.get('ok') and p.get('result', {}).get('x') is not None:
                pose = p['result']; break
        except Exception:
            pass
    if not pose:
        log('enter_world', False, '玩家位姿超时')
        proc.kill(); sys.exit(1)
    log('enter_world', True, f"pose=({pose['x']:.1f},{pose['y']:.1f},{pose['z']:.1f})")
    px, py, pz = pose['x'], pose['y'], pose['z']

    # 防 Forge LootModifierManager 竞态：世界生成/水流 postProcess 会在资源未就绪窗口触发掉落崩溃。
    # 对策：loadWorld 后等待足够久——世界生成完（Preparing spawn）+ 资源就绪 + 静置观察无 ERROR。
    print('等待世界生成与资源就绪（最长 120s）...')
    res_dl = time.time() + 120
    settled = False
    while time.time() < res_dl:
        try:
            t = read_log()
            if 'Preparing spawn area' in t and 'logged in' in t:
                # 玩家已进入后，再观察 25s 无新 ERROR 才放行
                if not settled:
                    settled = True
                    settle_start = time.time()
                if time.time() - settle_start >= 25:
                    break
            elif settled and 'unexpected exception' in t:
                settled = False  # 有异常重置，继续等
        except Exception:
            pass
        time.sleep(5)
    time.sleep(5)

    # 清残留相机
    try:
        lst = ws_send({'id': 'cl', 'action': 'camera.list'}, timeout=8)
        for c in lst.get('result', {}).get('cameras', []):
            ws_send({'id': f"del{c['id']}", 'action': 'camera.delete', 'params': {'id': c['id']}}, timeout=8)
    except Exception:
        pass

    # 放宽远裁剪面（渲染距离8→far仅128格，+200格外的已同步区块会被裁掉）
    try:
        r = ws_send({'id': 'fp', 'action': 'config.set', 'params': {'key': 'far_plane', 'value': 512}}, timeout=10)
        print('far_plane=512:', r.get('ok'))
    except Exception as e:
        print('far_plane 设置失败:', e)

    # +200 格，水平朝向玩家
    far_x = px + 200.0
    r = ws_send({'id': 'c1', 'action': 'camera.create', 'params': {
        'x': far_x, 'y': py + 30.0, 'z': pz, 'yaw': 180.0, 'pitch': 55.0, 'fov': 70}}, timeout=20)
    log('far_camera_create', r.get('ok', False), r)
    cam_id = r.get('result', {}).get('id', 1) if r.get('ok') else 1

    print('等待 60s（ticket+sync+首帧）...')
    time.sleep(60)
    text = read_log()
    sync_vals, unload_vals = log_values(text)
    if sync_vals:
        synced, desired = sync_vals[-1]
        ok_sync = desired > 0 and synced >= max(20, int(desired * 0.6))
        log('sync_log', ok_sync, f'synced={synced} desired={desired} samples={len(sync_vals)}')
    else:
        log('sync_log', False, '未找到 [CHUNK/SYNC] 已同步 日志')

    # 取帧前查 health
    try:
        import urllib.request as _ur
        h = json.loads(_ur.urlopen(f'{HTTP}/health', timeout=8).read())
        print('health: rendered=', h.get('video_rendered_frames'), 'failed=', h.get('video_failed_frames'),
              'readback=', h.get('video_readback_frames'), 'multi=', h.get('multi_rendered'))
    except Exception as e:
        print('health 查询失败:', e)

    # HTTP 取帧（重试）
    jpg = os.path.join(EV, 'artifacts', 'far-terrain.jpg')
    frame = None
    for _ in range(90):
        try:
            with urllib.request.urlopen(f'{HTTP}/camera/{cam_id}/frame', timeout=8) as resp:
                if resp.status == 200:
                    frame = resp.read()
                    if len(frame) > 5000:
                        break
        except Exception:
            pass
        time.sleep(0.5)
    if frame:
        open(jpg, 'wb').write(frame)
        log('http_frame', True, f'{len(frame)}B -> {jpg}')
    else:
        log('http_frame', False, 'HTTP 帧超时/过小')
    analysis = None
    if frame:
        try:
            analysis = analyze_frame(jpg)
            ok_px = analysis['lower_row_ratio'] >= 0.60 and analysis['lower_rows_non_sky_60'] >= 1
            log('terrain_pixels', ok_px, analysis)
            json.dump(analysis, open(os.path.join(EV, 'pixel-analysis.json'), 'w', encoding='utf-8'),
                      ensure_ascii=False, indent=2)
        except Exception as e:
            log('terrain_pixels', False, e)
    else:
        log('terrain_pixels', False, '无帧')

    # 卸载验证：删除相机，等待 15s
    try:
        ws_send({'id': 'delcam', 'action': 'camera.delete', 'params': {'id': cam_id}}, timeout=10)
    except Exception:
        pass
    time.sleep(15)
    text2 = read_log()
    _, unload2 = log_values(text2)
    unload_total = unload2[-1] if unload2 else 0
    log('unload_log', unload_total > 0, f'unload_last={unload_total} samples={len(unload2)}')

    proc.kill()
    time.sleep(3)
    try:
        subprocess.run(['taskkill', '/F', '/IM', 'java.exe'], capture_output=True)
    except Exception:
        pass
    report = {'date': time.strftime('%Y-%m-%dT%H:%M:%S'),
              'phases': RESULTS,
              'summary': f"{sum(1 for x in RESULTS if x['ok'])}/{len(RESULTS)}",
              'pixel_analysis': analysis}
    json.dump(report, open(os.path.join(EV, 'report.json'), 'w', encoding='utf-8'),
              ensure_ascii=False, indent=2)
    print(f"\n===== S11 完成：PASS {report['summary']} =====")
    print('report:', os.path.join(EV, 'report.json'))

if __name__ == '__main__':
    main()
