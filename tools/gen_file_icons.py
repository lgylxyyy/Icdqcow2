#!/usr/bin/env python3
"""从 Iconify 的 **MDI（Material Design Icons）** 生成文件类型图标（Android VectorDrawable）。

为什么这样做：
    · MDI 是 Apache-2.0，24×24 单色 path，和 Miuix（Material 血统）是同一套视觉语言；
    · 单色 path 能在 Compose 里 `Icon(..., tint = 类型色)`——换配色/深浅色不用重出图；
    · 只把用得到的 40 多个落进 res/，不引依赖、不联网也能编（离线构建不受影响）。

数据来源（只在**生成时**需要网络，产物进 git）：
    https://cdn.jsdelivr.net/npm/@iconify-json/mdi/icons.json

用法：
    python3 tools/gen_file_icons.py            # 缺哪个补哪个
    python3 tools/gen_file_icons.py --force    # 全部重写

产物：
    app/src/main/res/drawable/ic_ft_<slug>.xml
"""
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/res/drawable"
CACHE = ROOT / "tools/.cache/mdi.json"
URL = "https://cdn.jsdelivr.net/npm/@iconify-json/mdi/icons.json"

# 用到的图标（MDI 名字）。新增类型时：这里加一行 → 跑脚本 → FileIcons.kt 里引用。
GLYPHS = [
    "folder", "folder-open", "file", "file-document", "text-box",
    "file-pdf-box", "file-word-box", "file-excel-box", "file-powerpoint-box",
    "file-image", "file-music", "file-video", "file-code", "file-cog",
    "folder-zip", "package-variant", "archive",
    "application", "microsoft-windows", "android", "puzzle", "cog", "memory",
    "format-font", "file-certificate", "file-key", "file-lock",
    "disc", "harddisk", "usb-flash-drive", "database", "console",
    "link-variant", "file-chart", "file-table", "file-cabinet",
    "code-braces", "code-tags", "script-text", "xml", "web", "chip",
    "cellphone", "server", "email", "book-open-page-variant", "music-box",
]

PATH_RE = re.compile(r"<path\b([^>]*)/>", re.S)
D_RE = re.compile(r'\bd="([^"]+)"')
FILLRULE_RE = re.compile(r'fill-rule="([^"]+)"')


def load() -> dict:
    if not CACHE.exists():
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        print(f"下载 {URL} …")
        with urllib.request.urlopen(URL, timeout=60) as r:
            CACHE.write_bytes(r.read())
    return json.loads(CACHE.read_text(encoding="utf-8"))


def vector_xml(slug: str, body: str) -> str:
    paths = []
    for attrs in PATH_RE.findall(body):
        m = D_RE.search(attrs)
        if not m:
            continue
        d = m.group(1)
        fill_type = ""
        fr = FILLRULE_RE.search(attrs)
        if fr and fr.group(1) == "evenodd":
            fill_type = '\n        android:fillType="evenOdd"'
        paths.append(f'    <path\n        android:fillColor="#FF000000"\n'
                     f'        android:pathData="{d}"{fill_type} />')
    if not paths:
        raise SystemExit(f"× {slug}: body 里没找到 path")
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- 生成文件：tools/gen_file_icons.py · 来源 Iconify MDI (Apache-2.0) · {slug} -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n    android:height="24dp"\n'
        '    android:viewportWidth="24"\n    android:viewportHeight="24">\n'
        + "\n".join(paths) + "\n</vector>\n"
    )


def main() -> int:
    force = "--force" in sys.argv
    icons = load()["icons"]
    OUT.mkdir(parents=True, exist_ok=True)
    made = skipped = 0
    for slug in GLYPHS:
        if slug not in icons:
            print(f"× MDI 里没有 {slug}")
            return 2
        dst = OUT / f"ic_ft_{slug.replace('-', '_')}.xml"
        if dst.exists() and not force:
            skipped += 1
            continue
        dst.write_text(vector_xml(slug, icons[slug]["body"]), encoding="utf-8")
        made += 1
    print(f"生成 {made} 个，跳过 {skipped} 个 → {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())