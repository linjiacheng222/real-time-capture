#!/usr/bin/env python3
"""S2.4 directional audio PCM analysis.

Usage: python scripts/analyze-s2-directional.py <evidence-dir>
Reads pcm-<dir>-baseline.raw / pcm-<dir>-sound.raw (16-bit little-endian stereo 44100Hz)
and prints per-channel RMS and L-R difference; writes analysis.txt.
"""
import math
import struct
import sys
from pathlib import Path

def read_pcm(path: Path):
    data = path.read_bytes()
    n = len(data) // 2
    vals = struct.unpack("<%dh" % n, data[: n * 2])
    return vals

def channel_stats(vals):
    left = vals[0::2]
    right = vals[1::2]
    def rms(x):
        return math.sqrt(sum(v * v for v in x) / max(1, len(x)))
    def peak(x):
        return max((abs(v) for v in x), default=0)
    return rms(left), rms(right), peak(left), peak(right)

def main():
    if len(sys.argv) < 2:
        print("usage: analyze-s2-directional.py <evidence-dir>")
        return 2
    root = Path(sys.argv[1])
    directions = ["front", "back", "left", "right", "up", "down", "flip-left", "flip-right"]
    lines = ["direction,baseline_chunks? no,pcm_bytes,left_rms,right_rms,total_rms,lr_diff,peak"]
    out_lines = []
    for d in directions:
        base = root / f"pcm-{d}-baseline.raw"
        snd = root / f"pcm-{d}-sound.raw"
        if not snd.exists():
            out_lines.append(f"{d}: missing sound pcm")
            continue
        vals = read_pcm(snd)
        left, right, pl, pr = channel_stats(vals)
        total = math.sqrt((left * left + right * right) / 2.0)
        diff = left - right
        out_lines.append(
            f"{d}: bytes={snd.stat().st_size} left_rms={left:.2f} right_rms={right:.2f} "
            f"total_rms={total:.2f} L-R={diff:.2f} peak=({pl},{pr})"
        )
        if base.exists() and base.stat().st_size > 0:
            bvals = read_pcm(base)
            bl, br, _, _ = channel_stats(bvals)
            out_lines.append(f"  baseline: left_rms={bl:.2f} right_rms={br:.2f}")
    text = "\n".join(out_lines) + "\n"
    (root / "analysis.txt").write_text(text, encoding="utf-8")
    print(text)
    return 0

if __name__ == "__main__":
    sys.exit(main())
