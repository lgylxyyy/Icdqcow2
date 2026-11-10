#!/usr/bin/env python3
"""从 CHANGELOG.md 里取出某个版本的段落，供 GitHub Release 正文使用。

用法：python3 tools/changelog_section.py v0.1.0 > notes.md

这样 Release 正文与仓库里的变更记录**同一来源**，不会出现
「README 写一套、Release 页写另一套」的分叉。
"""
import re
import sys

CHANGELOG = "CHANGELOG.md"


def main() -> int:
    if len(sys.argv) != 2:
        print("用法：changelog_section.py <版本，如 v0.1.0>", file=sys.stderr)
        return 2
    version = sys.argv[1].lstrip("vV")
    text = open(CHANGELOG, encoding="utf-8").read()

    pattern = r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## \[|\Z)"
    m = re.search(pattern, text, re.S | re.M)
    if not m:
        print(f"CHANGELOG.md 里找不到版本 {version}", file=sys.stderr)
        return 1

    body = m.group(1).strip()
    # 去掉文件末尾的链接定义（它们属于整个文件，不属于这一版）
    body = re.split(r"\n\[[^\]]+\]: ", body)[0].rstrip()
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
