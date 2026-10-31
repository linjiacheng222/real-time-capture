#!/usr/bin/env python3
"""T1.7 映射校验脚本（I1.7.3 第①层）。

校验 docs/mapping-table-data.json 中：
  - class 行：Yarn 名 → intermediary → official(obf) → Mojmap 全链一致性；
  - forge-event 行：Forge 类在给定 Forge mapped jar 中存在（编译期可引用）。
映射数据首次运行时下载并缓存到 build/mappings-cache/（不提交，避免大文件入库）。

用法（工程根执行）：
  python scripts/verify-mappings.py
  python scripts/verify-mappings.py --forge-jar <forge-recomp.jar> --json docs/mapping-table-data.json --evidence build/mappings-cache/verify-mappings.txt

退出码：0=全部 PASS；1=存在 FAIL；2=数据/下载错误。
"""
import argparse
import io
import json
import os
import re
import sys
import urllib.request
import zipfile

YARN_URL = "https://maven.fabricmc.net/net/fabricmc/yarn/1.20.1+build.10/yarn-1.20.1+build.10-v2.jar"
INTERMEDIARY_URL = "https://maven.fabricmc.net/net/fabricmc/intermediary/1.20.1/intermediary-1.20.1-v2.jar"
VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
VERSION_ID = "1.20.1"


def download(url, path):
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        print(f"[download] {url}")
        urllib.request.urlretrieve(url, path)
    return path


def read_zip_entry_bytes(zip_path, entry_name):
    with zipfile.ZipFile(zip_path) as zf:
        return zf.read(entry_name)


def parse_tiny_v2_classes(data):
    """解析 tiny v2 类映射；返回 (namespaces, list[dict[ns]=class_name])。"""
    lines = data.decode("utf-8").splitlines()
    namespaces = None
    entries = []
    for line in lines:
        if not line or line.startswith("#"):
            continue
        if line.startswith("tiny\t"):
            parts = line.split("\t")
            namespaces = parts[3:]
            continue
        if line.startswith("c\t"):
            parts = line.split("\t")
            if namespaces and len(parts) >= 1 + len(namespaces):
                entries.append(dict(zip(namespaces, parts[1:])))
    if not namespaces:
        raise ValueError("tiny 文件缺少 header（namespaces 未找到）")
    return namespaces, entries


def parse_mojang_official(client_txt):
    """解析 Mojang official mappings（client.txt）；返回 official(obf) -> mojmap 类映射。"""
    official_to_mojmap = {}
    with io.open(client_txt, "r", encoding="utf-8") as fh:
        for line in fh:
            m = re.match(r"^(\S+) -> (\S+):$", line.strip())
            if m:
                mojmap, official = m.group(1), m.group(2)
                official_to_mojmap[official] = mojmap
    return official_to_mojmap


def load_manually(client_txt_dir):
    manifest = json.loads(io.open(os.path.join(client_txt_dir, "version_manifest_v2.json"), encoding="utf-8").read())
    target = next(v for v in manifest["versions"] if v["id"] == VERSION_ID)
    version_meta = json.loads(urllib.request.urlopen(target["url"]).read().decode("utf-8"))
    return version_meta["downloads"]["client_mappings"]["url"]


def read_zip_class_names(jar_path):
    names = set()
    with zipfile.ZipFile(jar_path) as zf:
        for name in zf.namelist():
            if name.endswith(".class"):
                names.add(name[:-6])
    return names


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", default=os.path.join("docs", "mapping-table-data.json"))
    parser.add_argument("--cache-dir", default=os.path.join("build", "mappings-cache"))
    parser.add_argument("--forge-jar", default="")
    parser.add_argument("--evidence", default="")
    args = parser.parse_args()

    project_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    data_path = os.path.abspath(os.path.join(project_root, args.json))
    cache = os.path.abspath(os.path.join(project_root, args.cache_dir))
    rows = json.loads(io.open(data_path, encoding="utf-8").read())["rows"]

    try:
        yarn_jar = download(YARN_URL, os.path.join(cache, "yarn-1.20.1+build.10-v2.jar"))
        inter_jar = download(INTERMEDIARY_URL, os.path.join(cache, "intermediary-1.20.1-v2.jar"))
        manifest_path = download(VERSION_MANIFEST_URL, os.path.join(cache, "version_manifest_v2.json"))
        client_txt = os.path.join(cache, "client-1.20.1.txt")
        if not os.path.exists(client_txt):
            manifest = json.loads(io.open(manifest_path, encoding="utf-8").read())
            target = next(v for v in manifest["versions"] if v["id"] == VERSION_ID)
            version_meta = json.loads(urllib.request.urlopen(target["url"]).read().decode("utf-8"))
            url = version_meta["downloads"]["client_mappings"]["url"]
            download(url, client_txt)

        yarn_ns, yarn_entries = parse_tiny_v2_classes(read_zip_entry_bytes(yarn_jar, "mappings/mappings.tiny"))
        inter_ns, inter_entries = parse_tiny_v2_classes(read_zip_entry_bytes(inter_jar, "mappings/mappings.tiny"))
        yarn_map = {e["named"]: e["intermediary"] for e in yarn_entries if "named" in e and "intermediary" in e}
        inter_map = {e["official"]: e["intermediary"] for e in inter_entries if "official" in e and "intermediary" in e}
        official_to_mojmap = parse_mojang_official(client_txt)
    except Exception as exc:  # noqa: BLE001
        print(f"[ERROR] 映射数据准备失败：{exc}")
        return 2

    # intermediary tiny 首两 namespace 为 official, intermediary：inter_map[official] = intermediary
    intermediary_to_official = {v: k for k, v in inter_map.items()}
    # yarn tiny 首两 namespace 为 named, intermediary：yarn_map[named] = intermediary
    lines = []
    ok = True
    passed = failed = 0
    for row in rows:
        rid = row.get("id", "?")
        if row.get("kind") == "class":
            yarn_name = row["yarn"]
            expected = row["mojmap"]
            intermediary = yarn_map.get(yarn_name)
            official = intermediary_to_official.get(intermediary) if intermediary else None
            actual = official_to_mojmap.get(official) if official else None
            actual_norm = actual.replace("/", ".") if actual else actual
            expected_norm = expected.replace("/", ".") if expected else expected
            good = actual_norm == expected_norm
            status = "PASS" if good else "FAIL"
            if good:
                passed += 1
            else:
                failed += 1
                ok = False
            lines.append(f"[{status}] {rid} {yarn_name} -> {intermediary} -> {official} -> {actual}（期望 {expected}）")
        elif row.get("kind") == "forge-event":
            if args.forge_jar:
                present = row["forge"] in read_zip_class_names(args.forge_jar)
            else:
                present = None
            status = "PASS" if present else ("SKIP" if present is None else "FAIL")
            if present:
                passed += 1
            elif present is False:
                failed += 1
                ok = False
            lines.append(f"[{status}] {rid} Forge 类存在性：{row['forge']}（Fabric 对照：{row.get('fabricEvent', '-')}）")
        else:
            lines.append(f"[SKIP] {rid} 未知行类型 {row.get('kind')}")

    lines.append(f"\n映射命名空间：yarn={yarn_ns}；intermediary={inter_ns}")
    lines.append(f"结果：PASS={passed} FAIL={failed}（class 行做全链校验；forge-event 行校验 Forge 类存在性）")
    text = "\n".join(lines)
    print(text)
    if args.evidence:
        os.makedirs(os.path.dirname(os.path.abspath(args.evidence)), exist_ok=True)
        io.open(os.path.abspath(args.evidence), "w", encoding="utf-8", newline="\n").write(text + "\n")
        print(f"[evidence] {os.path.abspath(args.evidence)}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
