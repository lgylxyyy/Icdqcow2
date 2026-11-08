#!/usr/bin/env python3
"""check_i18n.py — 文案表与工具真实能力对账

检查三件事（缺一项就退出非零，防止"加了命令忘了文案"）：
  1. imgcmd --list 里的每个命令 / 参数名 / feature，strings.json 都有中英文；
  2. imgtool/src/*.c 里 img_result_fail() 用到的每个 error_code，都有 cause+suggest；
  3. 反向：strings.json 里没有已经失效的旧 key（拼写错误也能抓出来）。

用法：
  python3 app/i18n/check_i18n.py
  python3 app/i18n/check_i18n.py --strings app/i18n/strings.json \
      --imgcmd imgtool/build/tools/imgcmd --src imgtool/src
"""
import argparse
import glob
import json
import os
import re
import subprocess
import sys

# 不是通过 img_result_fail 抛、但 App 会看到的事件级错误码
# 自动提取只覆盖"码字面量出现在 img_result_fail(...) 调用文本里"的情况。
# 这几个是先赋给变量、再传进 img_result_fail 的（提取器看不到），手工补进来，
# 免得文案表加了码反而被当成"多余项"：
EXTRA_ERROR_CODES = {
    "IO-TIMEOUT",        # img_event.c 的 I/O 看门狗
    "WIM-APPLY-FAIL",    # img_cmds.c: code = (rc==IMG_ECANCELLED)?...:"WIM-APPLY-FAIL"
    "CLONE-COPY-FAIL",   # img_cmds.c: code = (rc==IMG_EFULL)?...:"CLONE-COPY-FAIL"
    "ISO-READ-FAIL",     # img_cmds.c: iso_open_code(rc) 返回后传进 img_result_fail
    "ISO-FORMAT-BAD",    # 同上
    # file.copy 的错误码由 xcopy_code()/cde 变量传入 img_result_fail，
    # 自动提取只认调用里的字面量，所以在下面显式登记（2026-10-24）。
    "COPY-SRC-NOTFOUND", "COPY-DST-NOTFOUND", "COPY-DST-EXISTS",
    "COPY-DST-CONFLICT", "COPY-LBA-AMBIGUOUS", "COPY-DST-FS-BAD",
    "COPY-UNSUPPORTED", "COPY-NO-SPACE", "COPY-FILE-TOO-BIG",
    "COPY-NAME-TOO-LONG", "COPY-SAME-FILE", "COPY-INTO-SELF",
    "COPY-VERIFY-FAIL", "COPY-FAIL",
    # 分区表文件（2026-10-26）：这三个码是 img_ptfile_load() 里塞进 code 变量再传出来的
    "PT-OVERLAP", "PT-TOO-MANY", "PT-DUP-GUID",
    # 统一路径/移动/删除（2026-10-24）
    "PATH-BAD", "PATH-READONLY", "PATH-SRC-NOTFOUND", "PATH-DST-NOTFOUND",
    "PATH-FS-BAD", "PATH-PART-AMBIGUOUS",
    "MOVE-PARTIAL", "MOVE-HOST-DELETE-REFUSED", "MOVE-REFUSED",
    "RM-DIR-NOT-EMPTY", "RM-HOST-DELETE-REFUSED", "RM-REFUSED", "RM-ROOT-REFUSED",
    "NAME-INVALID",      # img_xcopy.c / img_cmds.c：Windows 文件名规则（2026-10-24 P0-2）
}


def load_strings(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    for sec in ("actions", "commands", "args", "errors", "features"):
        if sec not in data or not isinstance(data[sec], dict):
            sys.exit(f"❌ strings.json 缺少 {sec} 段")
    return data


def load_command_table(imgcmd):
    out = subprocess.check_output([imgcmd, "--list"], text=True)
    d = json.loads(out)
    return d


def extract_error_codes(src_dir):
    codes = set()
    pat = re.compile(r"img_result_fail\s*\((.*?)\)\s*;", re.S)
    for fn in glob.glob(os.path.join(src_dir, "*.c")):
        with open(fn, encoding="utf-8", errors="ignore") as f:
            text = f.read()
        for m in pat.finditer(text):
            # 按顶层逗号切参数（第三参是 error_code）
            depth, cur, args = 0, "", []
            for ch in m.group(1):
                if ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                if ch == "," and depth == 0:
                    args.append(cur)
                    cur = ""
                else:
                    cur += ch
            args.append(cur)
            # ★ 提取整个调用里出现的"码形状"字面量，而不只看第 3 个参数：
            #   三元/变量传码是合法的（wim.apply、img.clone 就是这么写的），
            #   旧写法只认第 3 参是纯字面量 → 漏掉 WIM-*/CLONE-COPY-FAIL 四个码，
            #   文案表缺了也不报（app/docs 手册 §9.4）。
            call = m.group(1)
            for lit in re.findall(r'"([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+)"', call):
                codes.add(lit)
    return codes | EXTRA_ERROR_CODES


def need_both(entry, path, problems):
    if not isinstance(entry, dict):
        problems.append(f"{path}: 不是对象")
        return
    for lang in ("zh", "en"):
        v = entry.get(lang)
        if not v or not str(v).strip():
            problems.append(f"{path}.{lang}: 空")


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.abspath(os.path.join(here, "..", ".."))          # app/Icdqcow2
    ws = os.path.abspath(os.path.join(here, "..", "..", ".."))       # /workspace
    # 工作区 2026-10-24 重排：工具在 tools/imgtool，产物在 build/host（tools/imgtool/build 是软链）
    cands = [os.path.join(root, "imgtool"),                # 老布局（万一）
             os.path.join(ws, "tools", "imgtool"),         # 新布局
             os.path.join(ws, "imgtool")]
    it = next((c for c in cands if os.path.isdir(c)), cands[1])
    cmd_cands = [os.path.join(it, "build", "tools", "imgcmd"),
                 os.path.join(ws, "build", "host", "tools", "imgcmd")]
    ap.add_argument("--strings", default=os.path.join(here, "strings.json"))
    ap.add_argument("--imgcmd", default=next((c for c in cmd_cands if os.path.exists(c)), cmd_cands[0]))
    ap.add_argument("--src", default=os.path.join(it, "src"))
    args = ap.parse_args()

    s = load_strings(args.strings)
    table = load_command_table(args.imgcmd)
    problems, warnings = [], []

    # 1) 命令 / 参数 / features
    cmds = {c["name"]: c for c in table["cmds"]}
    for name, c in sorted(cmds.items()):
        if name not in s["commands"]:
            problems.append(f"缺命令文案：{name}")
        else:
            e = s["commands"][name]
            for lang in ("zh", "en"):
                for field in ("label", "help"):
                    if not e.get(lang, {}).get(field, "").strip():
                        problems.append(f"commands.{name}.{lang}.{field}: 空")
    for name in s["commands"]:
        if name not in cmds:
            warnings.append(f"多余命令文案（命令已不存在）：{name}")

    arg_names = {a["name"] for c in cmds.values() for a in c["args"]}
    for a in sorted(arg_names):
        if a not in s["args"]:
            problems.append(f"缺参数文案：{a}")
        else:
            need_both(s["args"][a], f"args.{a}", problems)
    for a in s["args"]:
        if a not in arg_names:
            warnings.append(f"多余参数文案：{a}")

    feats = set(table.get("features", {}))
    for f in sorted(feats):
        if f not in s["features"]:
            problems.append(f"缺 feature 文案：{f}")
        else:
            need_both(s["features"][f], f"features.{f}", problems)
    for f in s["features"]:
        if f not in feats:
            warnings.append(f"多余 feature 文案：{f}")

    # 2) 错误码（原因 + 建议）
    codes = extract_error_codes(args.src)
    for code in sorted(codes):
        if code not in s["errors"]:
            problems.append(f"缺错误码文案：{code}")
            continue
        e = s["errors"][code]
        for lang in ("zh", "en"):
            if not e.get(lang, {}).get("cause", "").strip():
                problems.append(f"errors.{code}.{lang}.cause: 空")
            if not e.get(lang, {}).get("suggest", "").strip():
                problems.append(f"errors.{code}.{lang}.suggest: 空")
    for code in s["errors"]:
        if code not in codes:
            warnings.append(f"多余错误码文案：{code}")

    # 3) actions 非空
    for a, e in s["actions"].items():
        need_both(e, f"actions.{a}", problems)

    for w in warnings:
        print("⚠ " + w)
    if problems:
        print(f"\n❌ 文案表有 {len(problems)} 个问题：")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"✅ 文案表完整：{len(cmds)} 命令 · {len(arg_names)} 参数 · "
          f"{len(codes)} 错误码 · {len(feats)} features（中英各一份）")
    return 0


if __name__ == "__main__":
    sys.exit(main())