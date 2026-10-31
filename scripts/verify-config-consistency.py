#!/usr/bin/env python3
"""附录 B ↔ CaptureConfig.java 参数名一致性核验（M6/T6.2.3 初版）。

- 解析附录 B 表格中的反引号参数名（仅 TOML 参数；跳过构建元数据/未实装模块）；
- 解析 CaptureConfig.java 的 define/defineInRange/defineInList/define 键名；
- 输出双向差异；退出码 0=一致（允许 --allow-missing 列表）。

用法：
  python scripts/verify-config-consistency.py [--allow-missing "a,b"]
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APPENDIX = Path(r"E:\PCL\.minecraft\versions\1.20.1-Forge_Get_Test\实时捕获\实时捕获项目规划（草稿）"
                r"\实时捕获模组落地计划\附录B-全参数清单.md")
CONFIG = ROOT / "src/main/java/com/captureapi/core/config/CaptureConfig.java"

# 非 TOML / 构建参数 / 未实装模块（M8/M9 参数随对应模块实装登记）
NON_TOML = {"mod_id", "mod_group_id", "embeddium_version", "modernfix_version"}
M8_M9_PENDING = {
    "filter_directory", "overlay_directory", "overlay_enabled", "render_blacklist_file",
    "render_whitelist_file", "log_render_filter", "audio_blacklist_file", "audio_whitelist_file",
    "max_cameras", "default_permission", "permission_file",
}


def parse_appendix(text: str) -> set[str]:
    result = set()
    for line in text.splitlines():
        m = re.match(r"\|\s*`([a-z0-9_]+)`\s*\|", line)
        if m:
            result.add(m.group(1))
    return result


def parse_config(text: str) -> set[str]:
    return set(re.findall(r'\.(?:define|defineInRange|defineInList)\("([a-z0-9_]+)"', text))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--allow-missing", default="")
    args = parser.parse_args()
    allow = {x.strip() for x in args.allow_missing.split(",") if x.strip()} | M8_M9_PENDING

    appendix = parse_appendix(APPENDIX.read_text(encoding="utf-8"))
    config = parse_config(CONFIG.read_text(encoding="utf-8"))
    expected = {p for p in appendix if p not in NON_TOML}
    missing = sorted(expected - config)
    extra = sorted(config - expected)
    actual_missing = [p for p in missing if p not in allow]

    print(f"附录 B TOML 参数数: {len(expected)}，CaptureConfig 键数: {len(config)}")
    print(f"代码缺失: {missing or '无'}")
    print(f"代码多出: {extra or '无'}")
    print(f"允许缺失（未实装 M8/M9 等）: {sorted(allow & set(missing))}")
    if actual_missing or extra:
        if actual_missing:
            print("FAIL：存在未允许的缺失参数:", actual_missing)
        if extra:
            print("FAIL：存在附录 B 未登记的代码参数:", extra)
        return 1
    print("PASS：TOML 参数名集合一致（允许缺失项除外）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
