#!/usr/bin/env python3
"""CaptureAPI v0.5 WS 音视频对齐客户端示例（M5/I5.2.3）。

依赖：pip install websocket-client
用法：
  1) 游戏内安装捕获接口并启动对外服务（默认 ws://127.0.0.1:25581/）
  2) python ws_aligned_client.py [ws_url]
  3) 可选：另开脚本发送 camera.create 指令后即可收到视频帧

说明：本示例只演示「按时间戳消费」的骨架——二进制消息解析、时间戳排序/去重、
音画交叉差打印；真实播放（解码/渲染/声卡）归外部客户端。
"""
import json
import struct
import sys
import time

try:
    import websocket  # websocket-client
except ImportError:
    print("缺少依赖：pip install websocket-client")
    sys.exit(2)

WS_URL = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:25581/"

VIDEO_HEADER = struct.Struct("<qhhI")   # timestamp, width, height, payloadLength
AUDIO_HEADER = struct.Struct("<qqi")    # start, end, sampleCount

origin_ns = None
last_video_ts = 0
last_audio_mid = 0


def rel_ms(ns):
    global origin_ns
    if origin_ns is None:
        origin_ns = ns
    return (ns - origin_ns) / 1e6


def on_message(_ws, message):
    global last_video_ts, last_audio_mid
    if isinstance(message, str):
        # 控制响应：{id, ok, result?} / {id, ok:false, error:{code,message}}
        print("[CTRL]", message)
        return
    if len(message) >= VIDEO_HEADER.size:
        ts, width, height, payload = VIDEO_HEADER.unpack_from(message, 0)
        if payload == len(message) - VIDEO_HEADER.size and 0 < width < 8192 and 0 < height < 8192:
            if ts <= last_video_ts:
                print("[DROP] 视频时间戳回退/重复:", ts)
                return
            last_video_ts = ts
            print(f"[VIDEO] t={rel_ms(ts):9.3f} ms {width}x{height} jpeg={payload}B")
            return
    if len(message) >= AUDIO_HEADER.size:
        start, end, samples = AUDIO_HEADER.unpack_from(message, 0)
        if 0 < end - start < 1_000_000_000 and 0 < samples < 100000:
            mid = start + (end - start) // 2
            if mid <= last_audio_mid:
                print("[DROP] 音频时间戳回退/重复:", mid)
                return
            last_audio_mid = mid
            if last_video_ts:
                print(f"[SYNC] audio-video delta={rel_ms(mid) - rel_ms(last_video_ts):8.3f} ms")
            else:
                print(f"[AUDIO] t={rel_ms(mid):9.3f} ms samples={samples}")


def main():
    print("连接", WS_URL)
    ws = websocket.WebSocketApp(WS_URL, on_message=on_message)
    ws.run_forever()


if __name__ == "__main__":
    main()
