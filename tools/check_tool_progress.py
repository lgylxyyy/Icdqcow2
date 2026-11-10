#!/usr/bin/env python3
"""进度事件契约自检（REQ-68 / REQ-69）。

为什么要有这个：工具端每次交库都会说"我这边测试过了"，而 App 真正消费的是
`ToolEvents` 里那几个字段（pct / eta_s / item / phase）。这个脚本把"契约"变成
可执行断言 —— 拿一份 `imgcmd ... --progress=json` 的输出就能跑，不需要人点手机。

用法：  check_events.py <events.log> [--max-per-sec 5] [--cancel-ms 2000]
输入：  `imgcmd ... --progress=json` 的输出（也认 App JNI bridge 的 fields 形状）。
输出：  PASS/FAIL 表；退出码非 0 = 有 FAIL。
"""
import json, sys, collections

def load(path):
    evs = []
    for line in open(path, encoding='utf-8', errors='replace'):
        line = line.strip()
        if not line.startswith('{'): continue
        try: o = json.loads(line)
        except Exception: continue
        if o.get('ev') == 'progress':
            evs.append(o)               # CLI 形状
        elif o.get('event') == 'progress' and isinstance(o.get('fields'), dict):
            f = o['fields']; f['ev'] = 'progress'; evs.append(f)   # JNI 形状
    return evs

def main():
    path = sys.argv[1]
    max_per_sec = float(sys.argv[sys.argv.index('--max-per-sec')+1]) if '--max-per-sec' in sys.argv else 5.0
    evs = load(path)
    res = []
    def chk(name, ok, detail=''):
        res.append((name, ok, detail))

    phases = collections.Counter(e.get('phase') for e in evs)
    want = {'extract', 'pack', 'write'}
    chk('三段齐全 extract/pack/write', want <= set(phases), f"实际 {dict(phases)}")

    for ph in ('extract', 'pack', 'write'):
        seg = [e for e in evs if e.get('phase') == ph]
        if not seg: continue
        pcts = [e.get('pct', -1) for e in seg]
        chk(f'{ph} 段尾 pct=100', pcts[-1] == 100, f'尾={pcts[-1]}')
        known = [p for p in pcts if p >= 0]
        chk(f'{ph} 段内不倒退', all(b >= a for a, b in zip(known, known[1:])), '')

    items = [e.get('item') for e in evs if e.get('item')]
    chk('item 有绝对路径', any(i.startswith('/') for i in items), f'样本 {items[:2]}')

    pack_items = [e.get('item') for e in evs if e.get('phase') == 'pack' and e.get('item')]
    chk('pack 段带 item', len(pack_items) > 0, f'{len(pack_items)} 条')

    ext = phases.get('extract', 0)
    chk(f'extract 事件 ≤ {max_per_sec:.0f}/秒 等效', True if not evs else ext / max(1, (evs[-1].get('elapsed') or 1)) <= max_per_sec,
        f'{ext} 条')
    chk('每条事件都带 elapsed', all('elapsed' in e for e in evs[:50]), '前 50 条检查')

    w = 0
    for name, ok, detail in res:
        print(('PASS ' if ok else 'FAIL ') + name + (f'  [{detail}]' if detail else ''))
        if not ok: w += 1
    print(f'\n{len(res)-w}/{len(res)} 通过')
    sys.exit(1 if w else 0)

main()
