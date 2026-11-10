#!/usr/bin/env python3
"""把 partners.json 同步进两处生成物：

    1. app/src/main/AndroidManifest.xml 的 <queries> 段（PARTNERS:BEGIN/END 之间）
    2. app/src/main/java/<包路径>/core/PartnerList.kt

为什么用生成而不是手写：包名要在清单里声明（Android 11+ 的可见性），
代码里也要用同一份名单；两处各写一遍必然会忘。新增伙伴只改 partners.json。

用法：
    python3 tools/sync_partners.py          # 写入
    python3 tools/sync_partners.py --check  # 只检查是否同步（CI 用，不同步则非 0）
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# 包名从目录结构推导，避免在脚本里写死
APP_ORG = ROOT / "app/src/main/java/org"
PKG = "org." + next(d.name for d in APP_ORG.iterdir() if d.is_dir())
KOTLIN = ROOT / ("app/src/main/java/" + PKG.replace(".", "/") + "/core/PartnerList.kt")

DATA = ROOT / "partners.json"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
BEGIN = "<!-- PARTNERS:BEGIN 由 tools/sync_partners.py 生成，勿手改 -->"
END = "<!-- PARTNERS:END -->"


def manifest_block(packages):
    out = [BEGIN]
    for p in packages:
        out.append('        <package android:name="' + p + '" />')
    out.append("    " + END)
    return "\n".join(out)


def kotlin_file(packages, labels):
    pkg_lines = "\n".join('        "' + p + '",' for p in packages)
    label_lines = "\n".join('        "' + p + '" to "' + labels[p] + '",' for p in packages)
    head = (
        "package " + PKG + ".core\n\n"
        "/**\n"
        " * 受限制的伙伴应用包名（由 tools/sync_partners.py 从 partners.json 生成，勿手改）。\n"
        " *\n"
        " * 这些包名同时写进了 AndroidManifest 的 `<queries>`，否则 Android 11+ 查不到；\n"
        " * 只声明这一份名单，不用 QUERY_ALL_PACKAGES。\n"
        " */\n"
        "internal object PartnerList {\n"
        "    val PACKAGES: List<String> = listOf(\n"
    )
    mid = (
        "\n    )\n\n"
        "    /** 包名 → 显示名，用于界面提示。 */\n"
        "    val LABELS: Map<String, String> = mapOf(\n"
    )
    tail = (
        "\n    )\n\n"
        "    fun labelOf(pkg: String): String = LABELS[pkg] ?: pkg\n"
        "}\n"
    )
    return head + pkg_lines + mid + label_lines + tail


def main() -> int:
    check = "--check" in sys.argv
    data = json.loads(DATA.read_text(encoding="utf-8"))
    partners = data["partners"]
    packages = [p["package"] for p in partners]
    labels = {p["package"]: p["label"] for p in partners}

    manifest = MANIFEST.read_text(encoding="utf-8")
    start = manifest.index(BEGIN)
    end = manifest.index(END) + len(END)
    want_manifest = manifest[:start] + manifest_block(packages).lstrip() + manifest[end:]
    want_kotlin = kotlin_file(packages, labels)

    stale = []
    if manifest != want_manifest:
        stale.append(str(MANIFEST.relative_to(ROOT)))
    if not KOTLIN.exists() or KOTLIN.read_text(encoding="utf-8") != want_kotlin:
        stale.append(str(KOTLIN.relative_to(ROOT)))

    if check:
        if stale:
            print("x 生成物与 partners.json 不一致：" + "、".join(stale))
            print("  跑 python3 tools/sync_partners.py 后提交")
            return 1
        print("OK 伙伴名单已同步（" + str(len(packages)) + " 个包）")
        return 0

    MANIFEST.write_text(want_manifest, encoding="utf-8")
    KOTLIN.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN.write_text(want_kotlin, encoding="utf-8")
    print("已同步 " + str(len(packages)) + " 个伙伴包名 → 清单 + PartnerList.kt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
