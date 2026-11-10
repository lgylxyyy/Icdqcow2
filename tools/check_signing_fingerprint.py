#!/usr/bin/env python3
"""签名指纹单一来源校验。

发布证书的 SHA-256 在四个地方各写了一遍，任何一处改错都会导致
「CI 验签通过 / 文档写的却是另一串」这种自相矛盾，或者更糟：
密钥换了、README 还是旧的，用户照着比的指纹永远对不上。

参与比对的四处：
    1. app/src/main/java/.../core/Signing.kt   RELEASE_SHA256（App 内显示与比对用）
    2. .github/workflows/release.yml           EXPECTED_SHA256（CI 构建闸门）
    3. README.md                               「签名」章节表格
    4. README_EN.md                            Signing 章节表格

用法：
    python3 tools/check_signing_fingerprint.py        # 一致则 0，否则 1
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

HEX64 = r"[0-9a-fA-F]{64}"
COLON64 = r"[0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){31}"


def norm(s: str) -> str:
    return s.replace(":", "").strip().lower()


def find(path: Path, pattern: str) -> str | None:
    if not path.exists():
        return None
    m = re.search(pattern, path.read_text(encoding="utf-8"))
    return norm(m.group(1)) if m else None


def main() -> int:
    sources = {
        "core/Signing.kt": find(
            ROOT / "app/src/main/java/org/icdqcow2/core/Signing.kt",
            r'RELEASE_SHA256\s*=\s*"(' + HEX64 + r')"',
        ),
        "workflow EXPECTED_SHA256": find(
            ROOT / ".github/workflows/release.yml",
            r'EXPECTED_SHA256:\s*"(' + HEX64 + r')"',
        ),
        "README.md": find(ROOT / "README.md", r"\|\s*SHA-256 指纹\s*\|\s*`(" + COLON64 + r")`"),
        "README_EN.md": find(ROOT / "README_EN.md", r"\|\s*SHA-256 fingerprint\s*\|\s*`(" + COLON64 + r")`"),
    }

    missing = [k for k, v in sources.items() if not v]
    if missing:
        print("x 以下位置没找到指纹（或格式变了，正则匹配不到）：")
        for k in missing:
            print(f"   - {k}")
        return 2

    values = set(sources.values())
    if len(values) == 1:
        only = values.pop()
        print(f"OK 四处指纹一致：{only}")
        return 0

    print("x 指纹不一致 —— 换过密钥就把四处一起改：")
    for k, v in sources.items():
        print(f"   {k:26s} {v}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
