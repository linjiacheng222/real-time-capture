#!/usr/bin/env python3
"""Redline-1 magic-number audit (heuristic inventory, not a fixer).

Scans src/main/java for numeric literals and reports counts per file, excluding
CaptureConstants/CaptureConfig/simple small values/comments/strings. Output is an
inventory for review; redline 1 remains partial until reviewed/cleaned.
"""
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/java"
OUT = Path(__file__).resolve().parents[1] / "docs/evidence/S6/magic-audit.txt"
# 白名单/配置/常量类：允许定义数字
EXEMPT = {"CaptureConstants.java", "CaptureConfig.java", "CaptureParams.java", "RuntimeConfigWriter.java", "ProtocolConstants.java"}
SMALL = {"0", "1", "2", "3", "4", "5", "8", "16", "32", "64", "100", "1000", "1024", "441", "44100"}
NUM = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)(?![\w.])")
COMMENT = re.compile(r"//.*$")
BLOCK = re.compile(r"/\*.*?\*/", re.DOTALL)
TEXTBLOCK = re.compile(r'""".*?"""', re.DOTALL)

def blank_keep_lines(match):
    return "\n" * match.group(0).count("\n")
STRING = re.compile(r'"(?:\\.|[^"\\])*"')

def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    counts = Counter()
    samples = []
    files = sorted(ROOT.rglob("*.java"))
    for path in files:
        if path.name in EXEMPT:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        text = BLOCK.sub(blank_keep_lines, text)
        text = TEXTBLOCK.sub(blank_keep_lines, text)
        for lineno, line in enumerate(text.splitlines(), 1):
            code = COMMENT.sub("", line)
            code = STRING.sub('""', code)
            # 具名 static final 常量声明属于可登记白名单，不按内联魔法数字计
            if "static final" in code:
                continue
            for m in NUM.finditer(code):
                token = m.group(1)
                if token in SMALL:
                    continue
                counts[path.name] += 1
                if len(samples) < 400:
                    samples.append(f"{path.relative_to(ROOT)}:{lineno}: {token}")
    lines = []
    lines.append("Redline-1 magic-number heuristic audit")
    lines.append(f"files scanned: {len(files)}; exempt: {sorted(EXEMPT)}")
    lines.append(f"numeric literals not in small whitelist: {sum(counts.values())}")
    lines.append("")
    lines.append("top files:")
    for name, count in counts.most_common(30):
        lines.append(f"  {count:5d}  {name}")
    lines.append("")
    lines.append("samples (first 400):")
    lines.extend("  " + s for s in samples)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUT} :: literals={sum(counts.values())}, files={len(counts)}")

if __name__ == "__main__":
    sys.exit(main())
