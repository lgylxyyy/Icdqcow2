#!/usr/bin/env python3
"""文案自检：`Strings.kt` 引用的 key 必须在 `ui-strings.json` 里存在（且中英双语齐全）。

为什么要这个脚本：
    2026-10-26 出过一次事故 —— 往 JSON 里插新 key 时插错了大括号层级，
    9 个 key 被塞进了 `create.type.blank` 里面。JSON **仍然合法**、编译也通过，
    但运行时 `Strings.raw()` 查不到 → 界面直接显示 `create.pt.row` 这种裸 key。
    凡是"数据驱动的文案"，编译期都发现不了，只能靠这个脚本。

用法：
    python3 tools/check_strings.py            # 只报缺失/缺语言（非 0 退出）
    python3 tools/check_strings.py --unused   # 顺便列出没人用的 key
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/org/icdqcow2/ui/Strings.kt"
JSON = ROOT / "app/src/main/assets/i18n/ui-strings.json"
LANGS = ("zh", "en")


def main() -> int:
    show_unused = "--unused" in sys.argv
    try:
        data = json.loads(JSON.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        print(f"x JSON 解析失败: {e}")
        return 2
    table = data.get("strings") or {}
    src = KT.read_text(encoding="utf-8")
    keys = set(re.findall(r'raw\("([^"]+)"\)', src)) | set(re.findall(r'tpl\("([^"]+)"', src))
    # 动态拼接的 key（如 raw("cap.comp.$mode")）不是真实 key，跳过：
    # 这类前缀必须能在 JSON 里找到对应条目，否则才是真缺失。
    dyn_prefixes = set()
    for k in list(keys):
        if "$" in k:
            keys.discard(k)
            dyn_prefixes.add(k.split("$")[0])

    bad = []
    for key, cols in table.items():
        if not isinstance(cols, dict):
            bad.append((key, "不是一个对象（插错了大括号层级？）"))
            continue
        for lang in LANGS:
            if not str(cols.get(lang, "")).strip():
                bad.append((key, f"缺 {lang}"))
        # 写作说明：给维护者看这条文案出现在哪、给谁看、为什么这么写
        if not str(cols.get("note", "")).strip():
            bad.append((key, "缺 note（写作用注释：页面 · 组件 · 用途）"))

    missing = sorted(k for k in keys if k not in table)
    unused = sorted(k for k in table if k not in keys)

    print(f"Strings.kt 引用 {len(keys)} 条；JSON 共 {len(table)} 条")
    for pre in sorted(dyn_prefixes):
        if not any(k.startswith(pre) for k in table):
            bad.append((pre + "*", "动态拼接前缀没有任何对应 key"))
    if missing:
        print(f"x JSON 里没有（界面会显示裸 key）：{len(missing)}")
        for k in missing:
            print("   ", k)
    if bad:
        print(f"x 条目本身有问题：{len(bad)}")
        for k, why in bad:
            print(f"    {k}: {why}")
    if show_unused and unused:
        print(f"- 没人引用（可以删）：{len(unused)}")
        for k in unused:
            print("   ", k)
    if missing or bad:
        return 1
    print("OK 文案齐了")
    return 0


if __name__ == "__main__":
    sys.exit(main())